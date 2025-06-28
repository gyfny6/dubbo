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

import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.annotation.Service;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Dubbo Component Scan {@link Annotation},scans the classpath for annotated components that will be auto-registered as
 * Spring beans. Dubbo-provided {@link Service} and {@link Reference}.
 *
 * @see Service
 * @see Reference
 * @since 2.5.7
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(DubboComponentScanRegistrar.class)
public @interface DubboComponentScan {

    //和basePackages等价
    String[] value() default {};

    //要扫描包的s数组
    String[] basePackages() default {};

    //要扫描类的数组
    Class<?>[] basePackageClasses() default {};

}
