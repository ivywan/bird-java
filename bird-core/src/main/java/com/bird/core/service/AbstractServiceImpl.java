package com.bird.core.service;

import com.baomidou.mybatisplus.mapper.Wrapper;
import com.bird.core.Constants;
import com.bird.core.cache.CacheHelper;
import com.bird.core.exception.ExceptionHelper;
import com.bird.core.mapper.AbstractMapper;
import com.bird.core.mapper.PagedQueryParam;
import com.bird.core.model.AbstractModel;
import com.bird.core.service.query.PagedListResultDTO;
import com.bird.core.utils.DozerHelper;
import com.bird.core.utils.InstanceHelper;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 业务逻辑层基类
 * 继承基类后必须配置CacheConfig(cacheNames="")
 *
 * Created by liuxx on 2017/5/12.
 */
public abstract class AbstractServiceImpl<T extends AbstractModel> implements AbstractService<T>, ApplicationContextAware {
    protected Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    protected AbstractMapper<T> mapper;
    protected ApplicationContext applicationContext;

    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 定义通用的查询接口（支持查询、分页、排序）
     * 支持灵活组装查询数据源
     * 支持灵活控制返回的字段
     *
     * @param param 筛选条件
     * @return 查询的结果
     */
    public PagedListResultDTO queryPagedList(PagedQueryParam param) {
        Long totalCount = mapper.queryTotalCount(param);
        List<Map> items = mapper.queryPagedList(param);
        return new PagedListResultDTO(totalCount, items);
    }

    /**
     * 根据id集合获取数据
     *
     * @param ids id集合
     */
    public List<T> getList(List<Long> ids) {
        List<T> list = InstanceHelper.newArrayList();
        if (ids != null) {
            for (int i = 0; i < ids.size(); i++) {
                list.add(null);
            }
            ExecutorService executorService = Executors.newFixedThreadPool(10);
            for (int i = 0; i < ids.size(); i++) {
                final int index = i;
                executorService.execute(() -> list.set(index, queryById(ids.get(index))));
            }
            executorService.shutdown();
            try {
                executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                logger.error("awaitTermination", "", e);
            }
        }
        return list;
    }

    /**
     * 根据id集合获取指定类型的数据
     *
     * @param ids id集合
     * @param cls 返回的数据类型
     */
    public <K> List<K> getList(List<Long> ids, Class<K> cls) {
        List<K> list = InstanceHelper.newArrayList();
        if (ids != null) {
            for (int i = 0; i < ids.size(); i++) {
                list.add(null);
            }
            ExecutorService executorService = Executors.newFixedThreadPool(10);
            for (int i = 0; i < ids.size(); i++) {
                final int index = i;
                executorService.execute(() -> {
                    T t = queryById(ids.get(index));
                    K k = InstanceHelper.to(t, cls);
                    list.set(index, k);
                });
            }
            executorService.shutdown();
            try {
                executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                logger.error("awaitTermination", "", e);
            }
        }
        return list;
    }

    /**
     * 逻辑删除
     *
     * @param id     数据id
     * @param userId 当前操作的用户id
     */
    @Transactional
    public void softDelete(Long id, Long userId) {
        try {
            T record = this.queryById(id);
            record.setDelFlag(1);
            record.setUpdateTime(new Date());
            record.setUpdateBy(userId);
            mapper.updateById(record);
            CacheHelper.getCache().set(getCacheKey(id), record);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * 物理删除
     *
     * @param id 数据id
     */
    @Transactional
    public void delete(Long id) {
        try {
            mapper.deleteById(id);
            CacheHelper.getCache().del(getCacheKey(id));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * 保存，包括新增与编辑
     *
     * @param record 数据
     */
    @Transactional
    public T save(T record) {
        try {
            record.setUpdateTime(new Date());
            if (record.getId() == null || record.getId() == 0) {
                record.setCreateTime(new Date());
                mapper.insert(record);
            } else {
                T org = this.queryById(record.getId());
                String lockKey = getLockKey(record.getId());
                if (CacheHelper.getLock(lockKey)) {
                    try {
                        T update = InstanceHelper.getDiff(org, record);
                        update.setId(record.getId());
                        mapper.updateById(update);
                        record = mapper.selectById(record.getId());
                        CacheHelper.getCache().set(getCacheKey(record.getId()), record);
                    } finally {
                        CacheHelper.unlock(lockKey);
                    }
                } else {
                    sleep(20);
                    return save(record);
                }
            }
        } catch (DuplicateKeyException e) {
            String msg = ExceptionHelper.getStackTraceAsString(e);
            logger.error(Constants.Exception_Head + msg, e);
            throw new RuntimeException("已经存在相同的配置.");
        } catch (Exception e) {
            String msg = ExceptionHelper.getStackTraceAsString(e);
            logger.error(Constants.Exception_Head + msg, e);
            throw new RuntimeException(msg);
        }
        return record;
    }

    protected void sleep(int millis) {
        try {
            Thread.sleep(RandomUtils.nextLong(10, millis));
        } catch (InterruptedException e) {
            logger.error("", e);
        }
    }

    /**
     * 根据id查询数据并缓存
     *
     * @param id
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public T queryById(Long id) {
        String key = getCacheKey(id);
        T record = (T) CacheHelper.getCache().get(key);
        if (record == null) {
            String lockKey = getLockKey(id);
            if (CacheHelper.getLock(lockKey)) {
                try {
                    record = mapper.selectById(id);
                    CacheHelper.getCache().set(key, record);
                } finally {
                    CacheHelper.unlock(lockKey);
                }
            } else {
                logger.debug(getClass().getSimpleName() + ":" + id + " retry queryById.");
                sleep(20);
                return queryById(id);
            }
        }
        return record;
    }

    /**
     * 查询符合条件的第一条数据
     *
     * @param entity 查询条件
     */
    public T selectOne(Wrapper<T> entity) {
        List<T> list = mapper.selectList(entity);
        if (list.size() == 0) return null;
        return list.get(0);
    }

    /**
     * 查询符合条件的数据集合
     *
     * @param entity 查询条件
     */
    public List<T> selectList(Wrapper<T> entity) {
        return mapper.selectList(entity);
    }

    /**
     * 获取缓存键值
     */
    protected String getCacheKey(Object id) {
        String cacheName = getCacheKey();
        return new StringBuilder(Constants.CACHE_NAMESPACE).append(cacheName).append(":").append(id).toString();
    }

    /**
     * 获取缓存键值
     */
    protected String getLockKey(Object id) {
        String cacheName = getCacheKey();
        return new StringBuilder(Constants.CACHE_NAMESPACE).append(cacheName).append(":LOCK:").append(id).toString();
    }

    /**
     * @return
     */
    private String getCacheKey() {
        Class<?> cls = getClass();
        String cacheName = Constants.cacheKeyMap.get(cls);
        if (StringUtils.isBlank(cacheName)) {
            CacheConfig cacheConfig = cls.getAnnotation(CacheConfig.class);
            if (cacheConfig == null || cacheConfig.cacheNames() == null || cacheConfig.cacheNames().length < 1) {
                cacheName = getClass().getName();
            } else {
                cacheName = cacheConfig.cacheNames()[0];
            }
            Constants.cacheKeyMap.put(cls, cacheName);
        }
        return cacheName;
    }
}
