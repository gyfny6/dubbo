/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config.spring.beans.factory.annotation;

import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.ServiceBean;
import org.apache.dubbo.config.spring.context.event.ServiceBeanExportedEvent;
import org.apache.dubbo.config.spring.util.AnnotationUtils;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.InjectionMetadata;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that Consumer service {@link Reference} annotated fields
 *
 * @since 2.5.7
 * 扫描被@Reference注解标注的类，创建对应的Spring BeanDefinition对象，从而创建Dubbo Reference Bean对象
 * AnnotationInjectedBeanPostProcessor用于支持自定义注解，注入对象的属性
 * 支持@Reference注解的属性注入
 */
public class ReferenceAnnotationBeanPostProcessor extends AnnotationInjectedBeanPostProcessor<Reference>
        implements ApplicationContextAware, ApplicationListener {

    /**
     * The bean name of {@link ReferenceAnnotationBeanPostProcessor}
     */
    public static final String BEAN_NAME = "referenceAnnotationBeanPostProcessor";

    /**
     * Cache size
     */
    private static final int CACHE_SIZE = Integer.getInteger(BEAN_NAME + ".cache.size", 32);

    /**
     * ReferenceBean缓存Map
     * Key是Reference Bean的名字
     */
    private final ConcurrentMap<String, ReferenceBean<?>> referenceBeanCache =
            new ConcurrentHashMap<String, ReferenceBean<?>>(CACHE_SIZE);

    /**
     * 本地的@Service
     * ReferenceBeanInvocationHandler 缓存 Map
     * KEY：Reference Bean 的名字
     */
    private final ConcurrentHashMap<String, ReferenceBeanInvocationHandler> localReferenceBeanInvocationHandlerCache =
            new ConcurrentHashMap<String, ReferenceBeanInvocationHandler>(CACHE_SIZE);

    /**
     * 使用属性进行注入的 @Reference Bean 的缓存 Map
     */
    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedFieldReferenceBeanCache =
            new ConcurrentHashMap<InjectionMetadata.InjectedElement, ReferenceBean<?>>(CACHE_SIZE);

    /**
     * 使用方法进行注入的 @Reference Bean 的缓存 Map
     */
    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedMethodReferenceBeanCache =
            new ConcurrentHashMap<InjectionMetadata.InjectedElement, ReferenceBean<?>>(CACHE_SIZE);

    private ApplicationContext applicationContext;

    /**
     * Gets all beans of {@link ReferenceBean}
     *
     * @return non-null read-only {@link Collection}
     * @since 2.5.9
     */
    public Collection<ReferenceBean<?>> getReferenceBeans() {
        return referenceBeanCache.values();
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected field.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedFieldReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedFieldReferenceBeanCache);
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected method.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedMethodReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedMethodReferenceBeanCache);
    }

    @Override
    protected Object doGetInjectedBean(Reference reference, Object bean, String beanName, Class<?> injectedType,
                                       InjectionMetadata.InjectedElement injectedElement) throws Exception {
        //1-获取Reference Bean的名字
        String referencedBeanName = buildReferencedBeanName(reference, injectedType);
        //2-创建Reference Bean对象
        ReferenceBean referenceBean = buildReferenceBeanIfAbsent(referencedBeanName, reference, injectedType, getClassLoader());
        //3-缓存到 injectedFieldReferenceBeanCache or injectedMethodReferenceBeanCache 中
        cacheInjectedReferenceBean(referenceBean, injectedElement);
        //4-创建Proxy代理对象
        Object proxy = buildProxy(referencedBeanName, referenceBean, injectedType);

        return proxy;
    }

    private Object buildProxy(String referencedBeanName, ReferenceBean referenceBean, Class<?> injectedType) {
        InvocationHandler handler = buildInvocationHandler(referencedBeanName, referenceBean);
        Object proxy = Proxy.newProxyInstance(getClassLoader(), new Class[]{injectedType}, handler);
        return proxy;
    }

    private InvocationHandler buildInvocationHandler(String referencedBeanName, ReferenceBean referenceBean) {
        //从localReferenceBeanInvocationHandlerCache缓存中获得ReferenceBeanInvocationHandler对象
        ReferenceBeanInvocationHandler handler = localReferenceBeanInvocationHandlerCache.get(referencedBeanName);

        if (handler == null) {
            handler = new ReferenceBeanInvocationHandler(referenceBean);
        }
        //根据Dubbo服务是远程的还是本地的，做不同的处理

        //【本地】判断如果applicationContext中已经初始化，说明是本地的 @Service Bean，则添加到localReferenceBeanInvocationHandlerCache缓存中。
        // 等到本地的@Service Bean暴露后，再进行初始化
        if (applicationContext.containsBean(referencedBeanName)) { // Is local @Service Bean or not ?
            // ReferenceBeanInvocationHandler's initialization has to wait for current local @Service Bean has been exported.
            localReferenceBeanInvocationHandlerCache.put(referencedBeanName, handler);
        } else {
            //【远程】判断若果 applicationContext 中未初始化，说明是远程的 @Service Bean 对象，则立即进行初始化
            //远程的Dubbo服务，理论上是已经存在的，此时直接进行初始化
            handler.init();
        }

        return handler;
    }

    private static class ReferenceBeanInvocationHandler implements InvocationHandler {

        private final ReferenceBean referenceBean;

        private Object bean;

        private ReferenceBeanInvocationHandler(ReferenceBean referenceBean) {
            this.referenceBean = referenceBean;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = null;
            try {
                //其实这里也是实现了懒加载，等使用的时候再初始化
                if (bean == null) { // If the bean is not initialized, invoke init()
                    // issue: https://github.com/apache/incubator-dubbo/issues/3429
                    init();
                }
                //调用bean的对应方法
                result = method.invoke(bean, args);
            } catch (InvocationTargetException e) {
                // re-throws the actual Exception.
                throw e.getTargetException();
            }
            return result;
        }

        private void init() {
            this.bean = referenceBean.get();
        }
    }

    @Override
    protected String buildInjectedObjectCacheKey(Reference reference, Object bean, String beanName,
                                                 Class<?> injectedType, InjectionMetadata.InjectedElement injectedElement) {

        String key = buildReferencedBeanName(reference, injectedType) +
                "#source=" + (injectedElement.getMember()) +
                "#attributes=" + AnnotationUtils.getAttributes(reference,getEnvironment(),true);

        return key;
    }

    private String buildReferencedBeanName(Reference reference, Class<?> injectedType) {

        AnnotationBeanNameBuilder builder = AnnotationBeanNameBuilder.create(reference, injectedType);

        builder.environment(getEnvironment());

        return getEnvironment().resolvePlaceholders(builder.build());
    }

    private ReferenceBean buildReferenceBeanIfAbsent(String referencedBeanName, Reference reference,
                                                     Class<?> referencedType, ClassLoader classLoader)
            throws Exception {
        //获取缓存的ReferenceBean
        ReferenceBean<?> referenceBean = referenceBeanCache.get(referencedBeanName);
        //不存在则进行创建，并添加到缓存referenceBeanCache
        if (referenceBean == null) {
            ReferenceBeanBuilder beanBuilder = ReferenceBeanBuilder
                    .create(reference, classLoader, applicationContext)
                    .interfaceClass(referencedType);
            referenceBean = beanBuilder.build();
            referenceBeanCache.put(referencedBeanName, referenceBean);
        }

        return referenceBean;
    }

    private void cacheInjectedReferenceBean(ReferenceBean referenceBean,
                                            InjectionMetadata.InjectedElement injectedElement) {
        if (injectedElement.getMember() instanceof Field) {
            injectedFieldReferenceBeanCache.put(injectedElement, referenceBean);
        } else if (injectedElement.getMember() instanceof Method) {
            injectedMethodReferenceBeanCache.put(injectedElement, referenceBean);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        //收到ServiceBean暴露完成的事件
        if (event instanceof ServiceBeanExportedEvent) {
            onServiceBeanExportEvent((ServiceBeanExportedEvent) event);
        } else if (event instanceof ContextRefreshedEvent) {
            onContextRefreshedEvent((ContextRefreshedEvent) event);
        }
    }

    private void onServiceBeanExportEvent(ServiceBeanExportedEvent event) {
        //获得ServiceBean对象
        ServiceBean serviceBean = event.getServiceBean();
        //初始化对应的ReferenceBeanInvocationHandler
        initReferenceBeanInvocationHandler(serviceBean);
    }

    private void initReferenceBeanInvocationHandler(ServiceBean serviceBean) {
        String serviceBeanName = serviceBean.getBeanName();
        //从localReferenceBeanInvocationHandlerCache缓存中移除
        ReferenceBeanInvocationHandler handler = localReferenceBeanInvocationHandlerCache.remove(serviceBeanName);
        //缓存中存在ServiceBean对应的ReferenceBeanInvocationHandler，说明它未初始化，调用init完成初始化
        if (handler != null) {
            handler.init();
        }
    }

    private void onContextRefreshedEvent(ContextRefreshedEvent event) {

    }


    @Override
    public void destroy() throws Exception {
        //父类销毁
        super.destroy();
        //清空缓存
        this.referenceBeanCache.clear();
        this.localReferenceBeanInvocationHandlerCache.clear();
        this.injectedFieldReferenceBeanCache.clear();
        this.injectedMethodReferenceBeanCache.clear();
    }
}