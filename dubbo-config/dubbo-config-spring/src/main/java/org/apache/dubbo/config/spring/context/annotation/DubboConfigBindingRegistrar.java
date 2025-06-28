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
package org.apache.dubbo.config.spring.context.annotation;

import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.spring.beans.factory.annotation.DubboConfigBindingBeanPostProcessor;
import org.apache.dubbo.config.spring.context.config.NamePropertyDefaultValueDubboConfigBeanCustomizer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.config.spring.context.config.NamePropertyDefaultValueDubboConfigBeanCustomizer.BEAN_NAME;
import static org.apache.dubbo.config.spring.util.BeanRegistrar.registerInfrastructureBean;
import static org.apache.dubbo.config.spring.util.PropertySourcesUtils.getSubProperties;
import static org.apache.dubbo.config.spring.util.PropertySourcesUtils.normalizePrefix;
import static org.springframework.beans.factory.support.BeanDefinitionBuilder.rootBeanDefinition;
import static org.springframework.beans.factory.support.BeanDefinitionReaderUtils.registerWithGeneratedName;

/**
 * {@link AbstractConfig Dubbo Config} binding Bean registrar
 *
 * @see EnableDubboConfigBinding
 * @see DubboConfigBindingBeanPostProcessor
 * @since 2.5.8
 * 负责处理@EnableDubboConfigBinding，注册相应的Dubbo AbstractConfig到Spring容器中
 */
public class DubboConfigBindingRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private final Log log = LogFactory.getLog(getClass());

    private ConfigurableEnvironment environment;

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        //1-获得@EnableDubboConfigBinding注解
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableDubboConfigBinding.class.getName()));
        //2-注册配置对应的Bean Definition对象
        registerBeanDefinitions(attributes, registry);

    }

    protected void registerBeanDefinitions(AnnotationAttributes attributes, BeanDefinitionRegistry registry) {
        //获得prefix前缀
        String prefix = environment.resolvePlaceholders(attributes.getString("prefix"));
        //获得type属性，对应AbstractConfig的哪一个实现类
        Class<? extends AbstractConfig> configClass = attributes.getClass("type");
        //获得multiple属性
        boolean multiple = attributes.getBoolean("multiple");
        //注册Dubbo Config Bean对象
        registerDubboConfigBeans(prefix, configClass, multiple, registry);

    }

    private void registerDubboConfigBeans(String prefix,
                                          Class<? extends AbstractConfig> configClass,
                                          boolean multiple,
                                          BeanDefinitionRegistry registry) {
        //获取以为prefix开头的配置属性
        Map<String, Object> properties = getSubProperties(environment.getPropertySources(), prefix);
        //如果配置为空，则无需创建
        if (CollectionUtils.isEmpty(properties)) {
            return;
        }
        //获取配置属性对应的bean名字的集合
        Set<String> beanNames = multiple ? resolveMultipleBeanNames(properties) :
                Collections.singleton(resolveSingleBeanName(properties, configClass, registry));

        //遍历名字集合，逐个注册
        for (String beanName : beanNames) {
            //注册Dubbo Config Bean对象
            registerDubboConfigBean(beanName, configClass, registry);
            //注册Dubbo Config对象对应的DubboConfigBindingBeanPostProcessor对象
            registerDubboConfigBindingBeanPostProcessor(prefix, beanName, multiple, registry);

        }

        registerDubboConfigBeanCustomizers(registry);

    }

    private void registerDubboConfigBean(String beanName, Class<? extends AbstractConfig> configClass,
                                         BeanDefinitionRegistry registry) {

        BeanDefinitionBuilder builder = rootBeanDefinition(configClass);

        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
        //注册到registry
        registry.registerBeanDefinition(beanName, beanDefinition);

    }

    private void registerDubboConfigBindingBeanPostProcessor(String prefix, String beanName, boolean multiple,
                                                             BeanDefinitionRegistry registry) {

        Class<?> processorClass = DubboConfigBindingBeanPostProcessor.class;

        BeanDefinitionBuilder builder = rootBeanDefinition(processorClass);

        String actualPrefix = multiple ? normalizePrefix(prefix) + beanName : prefix;
        //设置DubboConfigBindingBeanPostProcessor的构造参数，分别是AbstractConfig的前缀和beanName
        builder.addConstructorArgValue(actualPrefix).addConstructorArgValue(beanName);

        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();

        beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);

        //注册到registry中
        //因为此时Dubbo Config Bean还未创建，所以需要等它创建之后使用DubboConfigBindingBeanPostProcessor设置对象的值
        registerWithGeneratedName(beanDefinition, registry);

    }

    private void registerDubboConfigBeanCustomizers(BeanDefinitionRegistry registry) {
        registerInfrastructureBean(registry, BEAN_NAME, NamePropertyDefaultValueDubboConfigBeanCustomizer.class);
    }

    @Override
    public void setEnvironment(Environment environment) {

        Assert.isInstanceOf(ConfigurableEnvironment.class, environment);

        this.environment = (ConfigurableEnvironment) environment;

    }

    private Set<String> resolveMultipleBeanNames(Map<String, Object> properties) {

        Set<String> beanNames = new LinkedHashSet<String>();

        for (String propertyName : properties.keySet()) {

            int index = propertyName.indexOf(".");

            if (index > 0) {

                String beanName = propertyName.substring(0, index);

                beanNames.add(beanName);
            }

        }

        return beanNames;

    }

    private String resolveSingleBeanName(Map<String, Object> properties, Class<? extends AbstractConfig> configClass,
                                         BeanDefinitionRegistry registry) {
        //获取bean的名字
        String beanName = (String) properties.get("id");
        //如果未定义id，基于 Spring 提供的机制，生成对应的 Bean 的名字。例如说：org.apache.dubbo.config.ApplicationConfig#0
        if (!StringUtils.hasText(beanName)) {
            BeanDefinitionBuilder builder = rootBeanDefinition(configClass);
            beanName = BeanDefinitionReaderUtils.generateBeanName(builder.getRawBeanDefinition(), registry);
        }

        return beanName;

    }

}