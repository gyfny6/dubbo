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

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.spring.ServiceBean;
import org.apache.dubbo.config.spring.context.annotation.DubboClassPathBeanDefinitionScanner;

import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.apache.dubbo.config.spring.util.ObjectUtils.of;
import static org.springframework.beans.factory.support.BeanDefinitionBuilder.rootBeanDefinition;
import static org.springframework.context.annotation.AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR;
import static org.springframework.core.annotation.AnnotationUtils.findAnnotation;
import static org.springframework.util.ClassUtils.resolveClassName;

/**
 * {@link Service} Annotation
 * {@link BeanDefinitionRegistryPostProcessor Bean Definition Registry Post Processor}
 *
 * @since 2.5.8
 * 扫描标注了@Service的类
 */
public class ServiceAnnotationBeanPostProcessor implements BeanDefinitionRegistryPostProcessor, EnvironmentAware,
        ResourceLoaderAware, BeanClassLoaderAware {


    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 要扫描的包的集合
     */
    private final Set<String> packagesToScan;

    private Environment environment;

    private ResourceLoader resourceLoader;

    private ClassLoader classLoader;

    public ServiceAnnotationBeanPostProcessor(String... packagesToScan) {
        this(Arrays.asList(packagesToScan));
    }

    public ServiceAnnotationBeanPostProcessor(Collection<String> packagesToScan) {
        this(new LinkedHashSet<String>(packagesToScan));
    }

    public ServiceAnnotationBeanPostProcessor(Set<String> packagesToScan) {
        this.packagesToScan = packagesToScan;
    }

    //核心方法：扫描@Service注解标注的类
    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        //1.解析packagesToScan集合，因为可能存在占位符
        Set<String> resolvedPackagesToScan = resolvePackagesToScan(packagesToScan);

        if (!CollectionUtils.isEmpty(resolvedPackagesToScan)) {
            //2-扫描packagesToScan包，创建对应的Spring BeanDefinition对象，从而创建Dubbo Service Bean对象
            registerServiceBeans(resolvedPackagesToScan, registry);
        }
    }


    /**
     * Registers Beans whose classes was annotated {@link Service}
     *
     * @param packagesToScan The base packages to scan
     * @param registry       {@link BeanDefinitionRegistry}
     */
    private void registerServiceBeans(Set<String> packagesToScan, BeanDefinitionRegistry registry) {
        //1.1-创建DubboClassPathBeanDefinitionScanner，用于扫描指定包下符合条件的类，用于将每个符合条件的类创建BeanDefinition对象，从而创建Bean
        DubboClassPathBeanDefinitionScanner scanner =
                new DubboClassPathBeanDefinitionScanner(registry, environment, resourceLoader);
        //1.2-获得BeanNameGenerator并设置到DubboClassPathBeanDefinitionScanner中
        BeanNameGenerator beanNameGenerator = resolveBeanNameGenerator(registry);
        scanner.setBeanNameGenerator(beanNameGenerator);
        //1.3-设置注解类型过滤器(设置过滤获得带有@Service注解的类)
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));

        //2-遍历packagesToScan数组
        for (String packageToScan : packagesToScan) {
            //2.1-执行扫描
            scanner.scan(packageToScan);
            //2.2-创建扫描到的类的BeanDefinitionHolder集合
            Set<BeanDefinitionHolder> beanDefinitionHolders =
                    findServiceBeanDefinitionHolders(scanner, packageToScan, registry, beanNameGenerator);
            //2.3-注册到registry中
            if (!CollectionUtils.isEmpty(beanDefinitionHolders)) {
                for (BeanDefinitionHolder beanDefinitionHolder : beanDefinitionHolders) {
                    registerServiceBean(beanDefinitionHolder, registry, scanner);
                }
            }
        }

    }

    /**
     * It'd better to use BeanNameGenerator instance that should reference
     * {@link ConfigurationClassPostProcessor#componentScanBeanNameGenerator},
     * thus it maybe a potential problem on bean name generation.
     *
     * @param registry {@link BeanDefinitionRegistry}
     * @return {@link BeanNameGenerator} instance
     * @see SingletonBeanRegistry
     * @see AnnotationConfigUtils#CONFIGURATION_BEAN_NAME_GENERATOR
     * @see ConfigurationClassPostProcessor#processConfigBeanDefinitions
     * @since 2.5.8
     */
    private BeanNameGenerator resolveBeanNameGenerator(BeanDefinitionRegistry registry) {
        BeanNameGenerator beanNameGenerator = null;
        //如果registry是SingletonBeanRegistry，从中获取BeanNameGenerator
        if (registry instanceof SingletonBeanRegistry) {
            SingletonBeanRegistry singletonBeanRegistry = SingletonBeanRegistry.class.cast(registry);
            beanNameGenerator = (BeanNameGenerator) singletonBeanRegistry.getSingleton(CONFIGURATION_BEAN_NAME_GENERATOR);
        }
        //如果不存在，则创建AnnotationBeanNameGenerator
        if (beanNameGenerator == null) {
            beanNameGenerator = new AnnotationBeanNameGenerator();

        }
        return beanNameGenerator;
    }

    /**
     * Finds a {@link Set} of {@link BeanDefinitionHolder BeanDefinitionHolders} whose bean type annotated
     * {@link Service} Annotation.
     *
     * @param scanner       {@link ClassPathBeanDefinitionScanner}
     * @param packageToScan pachage to scan
     * @param registry      {@link BeanDefinitionRegistry}
     * @return non-null
     * @since 2.5.8
     * 创建在packageToScan下被扫描到的类的BeanDefinitionHolder
     */
    private Set<BeanDefinitionHolder> findServiceBeanDefinitionHolders(
            ClassPathBeanDefinitionScanner scanner, String packageToScan, BeanDefinitionRegistry registry,
            BeanNameGenerator beanNameGenerator) {
        //获得包packageToScan下符合条件的BeanDefinition集合
        Set<BeanDefinition> beanDefinitions = scanner.findCandidateComponents(packageToScan);

        //创建BeanDefinitionHolder集合
        Set<BeanDefinitionHolder> beanDefinitionHolders = new LinkedHashSet<BeanDefinitionHolder>(beanDefinitions.size());
        for (BeanDefinition beanDefinition : beanDefinitions) {
            //获得beanName
            String beanName = beanNameGenerator.generateBeanName(beanDefinition, registry);
            //创建BeanDefinitionHolder
            BeanDefinitionHolder beanDefinitionHolder = new BeanDefinitionHolder(beanDefinition, beanName);
            //添加到beanDefinitionHolders中
            beanDefinitionHolders.add(beanDefinitionHolder);

        }

        return beanDefinitionHolders;

    }

    /**
     * Registers {@link ServiceBean} from new annotated {@link Service} {@link BeanDefinition}
     *
     * @param beanDefinitionHolder
     * @param registry
     * @param scanner
     * @see ServiceBean
     * @see BeanDefinition
     */
    private void registerServiceBean(BeanDefinitionHolder beanDefinitionHolder, BeanDefinitionRegistry registry,
                                     DubboClassPathBeanDefinitionScanner scanner) {
        //1.1-解析Bean的类型
        Class<?> beanClass = resolveClass(beanDefinitionHolder);
        //1.2-获得类上的@Service注解
        Service service = findAnnotation(beanClass, Service.class);
        //1.3-获得Service接口
        Class<?> interfaceClass = resolveServiceInterfaceClass(beanClass, service);
        //1.4-获得Bean的名字
        String annotatedServiceBeanName = beanDefinitionHolder.getBeanName();
        //1.5-创建AbstractBeanDefinition对象
        AbstractBeanDefinition serviceBeanDefinition =
                buildServiceBeanDefinition(service, interfaceClass, annotatedServiceBeanName);

        //2-重新生成Bean的名字
        String beanName = generateServiceBeanName(service, interfaceClass, annotatedServiceBeanName);
        //3-检验scanner中是否已经存在beanName。若不存在，则进行注册
        if (scanner.checkCandidate(beanName, serviceBeanDefinition)) { // check duplicated candidate bean
            registry.registerBeanDefinition(beanName, serviceBeanDefinition);
        }
    }

    /**
     * Generates the bean name of {@link ServiceBean}
     *
     * @param service
     * @param interfaceClass           the class of interface annotated {@link Service}
     * @param annotatedServiceBeanName the bean name of annotated {@link Service}
     * @return ServiceBean@interfaceClassName#annotatedServiceBeanName
     * @since 2.5.9
     */
    private String generateServiceBeanName(Service service, Class<?> interfaceClass, String annotatedServiceBeanName) {

        AnnotationBeanNameBuilder builder = AnnotationBeanNameBuilder.create(service, interfaceClass);

        builder.environment(environment);

        return builder.build();

    }

    private Class<?> resolveServiceInterfaceClass(Class<?> annotatedServiceBeanClass, Service service) {
        //从@Service注解的interfaceClass属性获得接口
        Class<?> interfaceClass = service.interfaceClass();

        if (void.class.equals(interfaceClass)) {

            interfaceClass = null;
            //获得@Service注解的interfaceName属性
            String interfaceClassName = service.interfaceName();
            //如果存在，获得其对应的类
            if (StringUtils.hasText(interfaceClassName)) {
                if (ClassUtils.isPresent(interfaceClassName, classLoader)) {
                    interfaceClass = resolveClassName(interfaceClassName, classLoader);
                }
            }

        }
        //上面的流程没有获取到class，则从被注解的类上获得其实现的首个接口
        if (interfaceClass == null) {
            // Find all interfaces from the annotated class
            // To resolve an issue : https://github.com/apache/incubator-dubbo/issues/3251
            Class<?>[] allInterfaces = ClassUtils.getAllInterfacesForClass(annotatedServiceBeanClass);

            if (allInterfaces.length > 0) {
                interfaceClass = allInterfaces[0];
            }

        }

        Assert.notNull(interfaceClass,
                "@Service interfaceClass() or interfaceName() or interface class must be present!");

        Assert.isTrue(interfaceClass.isInterface(),
                "The type that was annotated @Service is not an interface!");

        return interfaceClass;
    }

    private Class<?> resolveClass(BeanDefinitionHolder beanDefinitionHolder) {

        BeanDefinition beanDefinition = beanDefinitionHolder.getBeanDefinition();

        return resolveClass(beanDefinition);

    }

    private Class<?> resolveClass(BeanDefinition beanDefinition) {

        String beanClassName = beanDefinition.getBeanClassName();

        return resolveClassName(beanClassName, classLoader);

    }

    private Set<String> resolvePackagesToScan(Set<String> packagesToScan) {
        Set<String> resolvedPackagesToScan = new LinkedHashSet<String>(packagesToScan.size());
        //遍历packagesToScan数组
        for (String packageToScan : packagesToScan) {
            if (StringUtils.hasText(packageToScan)) {
                //解析可能存在的占位符，添加到Set中
                String resolvedPackageToScan = environment.resolvePlaceholders(packageToScan.trim());
                resolvedPackagesToScan.add(resolvedPackageToScan);
            }
        }
        return resolvedPackagesToScan;
    }

    private AbstractBeanDefinition buildServiceBeanDefinition(Service service, Class<?> interfaceClass,
                                                              String annotatedServiceBeanName) {
        //创建ServiceBean的BeanDefinitionBuilder，
        BeanDefinitionBuilder builder = rootBeanDefinition(ServiceBean.class);
        //获得AbstractBeanDefinition
        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
        //获得MutablePropertyValues属性，后续通过向MutablePropertyValues添加属性，设置到BeanDefinition中
        MutablePropertyValues propertyValues = beanDefinition.getPropertyValues();

        //将@Service注解上的属性设置到propertyValues中(忽略ignoreAttributeNames)
        String[] ignoreAttributeNames = of("provider", "monitor", "application", "module", "registry", "protocol",
                "interface", "interfaceName");
        propertyValues.addPropertyValues(new AnnotationPropertyValuesAdapter(service, environment, ignoreAttributeNames));

        //ServiceBean的ref属性指向了标注了@Service对应的bean
        addPropertyReference(builder, "ref", annotatedServiceBeanName);
        //设置Service接口类全类名
        builder.addPropertyValue("interface", interfaceClass.getName());

        //添加provider对应的引用
        String providerConfigBeanName = service.provider();
        if (StringUtils.hasText(providerConfigBeanName)) {
            addPropertyReference(builder, "provider", providerConfigBeanName);
        }

        //添加monitor属性
        String monitorConfigBeanName = service.monitor();
        if (StringUtils.hasText(monitorConfigBeanName)) {
            addPropertyReference(builder, "monitor", monitorConfigBeanName);
        }

        //添加application属性对应的 ApplicationConfig Bean 对象
        String applicationConfigBeanName = service.application();
        if (StringUtils.hasText(applicationConfigBeanName)) {
            addPropertyReference(builder, "application", applicationConfigBeanName);
        }

        //添加 module 属性对应的 ModuleConfig Bean 对象
        String moduleConfigBeanName = service.module();
        if (StringUtils.hasText(moduleConfigBeanName)) {
            addPropertyReference(builder, "module", moduleConfigBeanName);
        }


        //添加 registries 属性对应的 RegistryConfig Bean 数组（一个或多个）
        String[] registryConfigBeanNames = service.registry();

        List<RuntimeBeanReference> registryRuntimeBeanReferences = toRuntimeBeanReferences(registryConfigBeanNames);

        if (!registryRuntimeBeanReferences.isEmpty()) {
            builder.addPropertyValue("registries", registryRuntimeBeanReferences);
        }

        //添加 protocols 属性对应的 ProtocolConfig Bean 数组（一个或多个
        String[] protocolConfigBeanNames = service.protocol();

        List<RuntimeBeanReference> protocolRuntimeBeanReferences = toRuntimeBeanReferences(protocolConfigBeanNames);

        if (!protocolRuntimeBeanReferences.isEmpty()) {
            builder.addPropertyValue("protocols", protocolRuntimeBeanReferences);
        }

        return builder.getBeanDefinition();

    }

    //在解析到依赖的Bean的时侯，解析器会依据依赖bean的name创建一个RuntimeBeanReference对像，将这个对像放入BeanDefinition的MutablePropertyValues中。
    private ManagedList<RuntimeBeanReference> toRuntimeBeanReferences(String... beanNames) {

        ManagedList<RuntimeBeanReference> runtimeBeanReferences = new ManagedList<RuntimeBeanReference>();

        if (!ObjectUtils.isEmpty(beanNames)) {

            for (String beanName : beanNames) {

                String resolvedBeanName = environment.resolvePlaceholders(beanName);

                runtimeBeanReferences.add(new RuntimeBeanReference(resolvedBeanName));
            }

        }

        return runtimeBeanReferences;

    }

    private void addPropertyReference(BeanDefinitionBuilder builder, String propertyName, String beanName) {
        String resolvedBeanName = environment.resolvePlaceholders(beanName);
        builder.addPropertyReference(propertyName, resolvedBeanName);
    }


    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

}