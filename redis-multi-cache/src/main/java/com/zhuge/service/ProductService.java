package com.zhuge.service;

import com.alibaba.fastjson.JSON;
import com.zhuge.common.RedisKeyPrefixConst;
import com.zhuge.common.RedisUtil;
import com.zhuge.dao.ProductDao;
import com.zhuge.model.Product;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class ProductService {

    @Autowired
    private ProductDao productDao;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private Redisson redisson;

    public static final Integer PRODUCT_CACHE_TIMEOUT = 60 * 60 * 24;
    public static final String EMPTY_CACHE = "{}";
    public static final String LOCK_PRODUCT_HOT_CACHE_PREFIX = "lock:product:hot_cache:";
    public static final String LOCK_PRODUCT_UPDATE_PREFIX = "lock:product:update:";
    public static Map<String, Product> productMap = new ConcurrentHashMap<>();

    @Transactional
    public Product create(Product product) {
        Product productResult = productDao.create(product);
        redisUtil.set(RedisKeyPrefixConst.PRODUCT_CACHE + productResult.getId(),
                JSON.toJSONString(productResult),
                genProductCacheTimeout(), TimeUnit.SECONDS);
        return productResult;
    }

    @Transactional
    public Product update(Product product) {
        Product productResult;
        //RLock updateProductLock = redisson.getLock(LOCK_PRODUCT_UPDATE_PREFIX + product.getId());
        RReadWriteLock readWriteLock = redisson.getReadWriteLock(LOCK_PRODUCT_UPDATE_PREFIX + product.getId());
        RLock writeLock = readWriteLock.writeLock();
        writeLock.lock();
        try {
            productResult = productDao.update(product);
            redisUtil.set(RedisKeyPrefixConst.PRODUCT_CACHE + productResult.getId(), JSON.toJSONString(productResult),
                    genProductCacheTimeout(), TimeUnit.SECONDS);
            productMap.put(RedisKeyPrefixConst.PRODUCT_CACHE + productResult.getId(), product);
        } finally {
            writeLock.unlock();
        }
        return productResult;
    }

    public Product get(Long productId) {
        Product product;
        String productCacheKey = RedisKeyPrefixConst.PRODUCT_CACHE + productId;

        // 获取缓存 null表示未获取到 空表示缓存值为空
        product = getProductFromCache(productCacheKey);
        if (product != null) {
            return product;
        }
        // DCL
        // 热点缓存
        RLock hotCacheLock = redisson.getLock(LOCK_PRODUCT_HOT_CACHE_PREFIX + productId);
        hotCacheLock.lock();
        // boolean result = hotCacheLock.tryLock(3, TimeUnit.SECONDS);
        try {
            product = getProductFromCache(productCacheKey);
            if (product != null) {
                return product;
            }

            // RLock updateProductLock = redisson.getLock(LOCK_PRODUCT_UPDATE_PREFIX + productId);
            // 读写锁，解决修改时读写互斥问题
            RReadWriteLock readWriteLock = redisson.getReadWriteLock(LOCK_PRODUCT_UPDATE_PREFIX + productId);
            RLock rLock = readWriteLock.readLock();
            rLock.lock();
            try {
                // 数据库查询
                product = productDao.get(productId);
                if (product != null) {
                    // 设置 redis 缓存
                    redisUtil.set(productCacheKey, JSON.toJSONString(product),
                            genProductCacheTimeout(), TimeUnit.SECONDS);
                    // 设置 JVM 缓存
                    productMap.put(productCacheKey, product);
                } else {
                    // 设置空缓存
                    redisUtil.set(productCacheKey, EMPTY_CACHE, genEmptyCacheTimeout(), TimeUnit.SECONDS);
                }
            } finally {
                rLock.unlock();
            }
        } finally {
            hotCacheLock.unlock();
        }
        return product;
    }


    private Integer genProductCacheTimeout() {
        // 读延期 24h
        return PRODUCT_CACHE_TIMEOUT + new Random().nextInt(5) * 60 * 60;
    }

    private Integer genEmptyCacheTimeout() {
        // 空缓存延期1-1.5min
        return 60 + new Random().nextInt(30);
    }

    private Product getProductFromCache(String productCacheKey) {
        // 多级缓存粗略实现 (JVM级别锁)
        Product product = productMap.get(productCacheKey);
        if (product != null) {
            return product;
        }
        // redis 中获取
        String productStr = redisUtil.get(productCacheKey);
        if (!StringUtils.isEmpty(productStr)) {
            // 空json表示为空缓存， 防止击穿
            if (EMPTY_CACHE.equals(productStr)) {
                // 空缓存设置延期
                redisUtil.expire(productCacheKey, genEmptyCacheTimeout(), TimeUnit.SECONDS);
                // 返回空对象
                return new Product();
            }
            product = JSON.parseObject(productStr, Product.class);
            // 读延期 24h
            redisUtil.expire(productCacheKey, genProductCacheTimeout(), TimeUnit.SECONDS);
        }
        return product;
    }

}