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

package org.apache.shenyu.client.springcloud.init;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.shenyu.client.apidocs.annotations.ApiDoc;
import org.apache.shenyu.client.core.client.AbstractContextRefreshedEventListener;
import org.apache.shenyu.client.core.constant.ShenyuClientConstants;
import org.apache.shenyu.client.core.exception.ShenyuClientIllegalArgumentException;
import org.apache.shenyu.client.springcloud.annotation.ShenyuSpringCloudClient;
import org.apache.shenyu.common.enums.ApiHttpMethodEnum;
import org.apache.shenyu.common.enums.ApiSourceEnum;
import org.apache.shenyu.common.enums.ApiStateEnum;
import org.apache.shenyu.common.enums.RpcTypeEnum;
import org.apache.shenyu.common.exception.ShenyuException;
import org.apache.shenyu.common.utils.IpUtils;
import org.apache.shenyu.common.utils.PathUtils;
import org.apache.shenyu.client.core.utils.PortUtils;
import org.apache.shenyu.register.client.api.ShenyuClientRegisterRepository;
import org.apache.shenyu.register.common.config.PropertiesConfig;
import org.apache.shenyu.register.common.dto.ApiDocRegisterDTO;
import org.apache.shenyu.register.common.dto.MetaDataRegisterDTO;
import org.apache.shenyu.register.common.dto.URIRegisterDTO;
import org.apache.shenyu.register.common.enums.EventType;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The type Spring cloud client event listener.
 */
public class SpringCloudClientEventListener extends AbstractContextRefreshedEventListener<Object, ShenyuSpringCloudClient> {

    private final Boolean isFull;
    
    private final Environment env;
    
    private final String servletContextPath;

    private final boolean addPrefixed;
    
    private final List<Class<? extends Annotation>> mappingAnnotation = new ArrayList<>(3);

    /**
     * Instantiates a new Spring cloud client bean post processor.
     *
     * @param clientConfig                   the shenyu client config
     * @param shenyuClientRegisterRepository the shenyuClientRegisterRepository
     * @param env                            the env
     */
    public SpringCloudClientEventListener(final PropertiesConfig clientConfig,
                                          final ShenyuClientRegisterRepository shenyuClientRegisterRepository,
                                          final Environment env) {
        super(clientConfig, shenyuClientRegisterRepository);
        Properties props = clientConfig.getProps();
        this.env = env;
        if (StringUtils.isBlank(getAppName())) {
            String errorMsg = "spring cloud param must config the appName";
            LOG.error(errorMsg);
            throw new ShenyuClientIllegalArgumentException(errorMsg);
        }
        this.addPrefixed = Boolean.parseBoolean(props.getProperty(ShenyuClientConstants.ADD_PREFIXED, Boolean.FALSE.toString()));
        this.isFull = Boolean.parseBoolean(props.getProperty(ShenyuClientConstants.IS_FULL, Boolean.FALSE.toString()));
        this.servletContextPath = env.getProperty("server.servlet.context-path", "");
        mappingAnnotation.add(ShenyuSpringCloudClient.class);
        mappingAnnotation.add(RequestMapping.class);
    }

    /**
     * buildApiDocDTO.
     *
     * @param bean  bean
     * @param method method
     * @return ApiDocRegisterDTO
     */
    @Override
    protected List<ApiDocRegisterDTO> buildApiDocDTO(final Object bean, final Method method) {
        final String contextPath = getContextPath();
        String apiDesc = Stream.of(method.getDeclaredAnnotations()).filter(item -> item instanceof ApiDoc).findAny().map(item -> {
            ApiDoc apiDoc = (ApiDoc) item;
            return apiDoc.desc();
        }).orElse("");
        Class<?> clazz = AopUtils.isAopProxy(bean) ? AopUtils.getTargetClass(bean) : bean.getClass();
        String superPath = buildApiSuperPath(clazz, AnnotatedElementUtils.findMergedAnnotation(clazz, getAnnotationType()));
        if (superPath.indexOf("*") > 0) {
            superPath = superPath.substring(0, superPath.lastIndexOf("/"));
        }
        RequestMapping requestMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (Objects.nonNull(requestMapping)) {
            List<ApiDocRegisterDTO> list = Lists.newArrayList();
            String produce = requestMapping.produces().length == 0 ? ShenyuClientConstants.MEDIA_TYPE_ALL_VALUE : String.join(",", requestMapping.produces());
            String consume = requestMapping.consumes().length == 0 ? ShenyuClientConstants.MEDIA_TYPE_ALL_VALUE : String.join(",", requestMapping.consumes());
            String[] values = requestMapping.value();
            for (String value : values) {
                String apiPath = contextPath + superPath + value;
                RequestMethod[] requestMethods = requestMapping.method();
                if (requestMethods.length == 0) {
                    requestMethods = RequestMethod.values();
                }
                for (RequestMethod requestMethod : requestMethods) {
                    ApiDocRegisterDTO build = ApiDocRegisterDTO.builder()
                            .consume(consume)
                            .produce(produce)
                            .httpMethod(ApiHttpMethodEnum.getValueByName(String.valueOf(requestMethod)))
                            .contextPath(contextPath)
                            .ext("{}")
                            .document("{}")
                            .version("v0.01")
                            .rpcType(RpcTypeEnum.SPRING_CLOUD.getName())
                            .apiDesc(apiDesc)
                            .apiPath(apiPath)
                            .apiSource(ApiSourceEnum.ANNOTATION_GENERATION.getValue())
                            .state(ApiStateEnum.PUBLISHED.getState())
                            .apiOwner("admin")
                            .eventType(EventType.REGISTER)
                            .build();
                    list.add(build);
                }
            }
            return list;
        } else {
            return apiDocRegisterDTOListByDeclaredAnnotations(contextPath, superPath, method, apiDesc);
        }
    }

    private List<ApiDocRegisterDTO> apiDocRegisterDTOListByDeclaredAnnotations(final String contextPath, final String superPath, final Method method, final String apiDesc) {
        List<ApiDocRegisterDTO> list = Lists.newArrayList();
        Map<ApiHttpMethodEnum, Triple<String[], String[], String[]>> methodConsumeProduceValueMap = httpMethodConsumeProduceValueMap(method);
        methodConsumeProduceValueMap.forEach((k, v) -> {
            String[] values = v.getRight();
            String consume = v.getLeft().length == 0 ? ShenyuClientConstants.MEDIA_TYPE_ALL_VALUE : String.join(",", v.getLeft());
            String produce = v.getMiddle().length == 0 ? ShenyuClientConstants.MEDIA_TYPE_ALL_VALUE : String.join(",", v.getMiddle());
            for (String value : values) {
                String apiPath = contextPath + superPath + value;
                ApiDocRegisterDTO build = ApiDocRegisterDTO.builder()
                        .consume(consume)
                        .produce(produce)
                        .httpMethod(k.getValue())
                        .contextPath(contextPath)
                        .ext("{}")
                        .document("{}")
                        .version("v0.01")
                        .rpcType(RpcTypeEnum.SPRING_CLOUD.getName())
                        .apiDesc(apiDesc)
                        .apiPath(apiPath)
                        .apiSource(ApiSourceEnum.ANNOTATION_GENERATION.getValue())
                        .state(ApiStateEnum.PUBLISHED.getState())
                        .apiOwner("admin")
                        .eventType(EventType.REGISTER)
                        .build();
                list.add(build);
            }
        });
        return list;
    }

    private Map<ApiHttpMethodEnum, Triple<String[], String[], String[]>> httpMethodConsumeProduceValueMap(final Method method) {
        Map<ApiHttpMethodEnum, Triple<String[], String[], String[]>> map = Maps.newHashMap();
        GetMapping getMapping = AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class);
        if (Objects.nonNull(getMapping)) {
            map.put(ApiHttpMethodEnum.GET, Triple.of(getMapping.consumes(), getMapping.produces(), getMapping.value()));
            return map;
        }
        PutMapping putMapping = AnnotatedElementUtils.findMergedAnnotation(method, PutMapping.class);
        if (Objects.nonNull(putMapping)) {
            map.put(ApiHttpMethodEnum.PUT, Triple.of(putMapping.consumes(), putMapping.produces(), putMapping.value()));
            return map;
        }
        PostMapping postMapping = AnnotatedElementUtils.findMergedAnnotation(method, PostMapping.class);
        if (Objects.nonNull(postMapping)) {
            map.put(ApiHttpMethodEnum.POST, Triple.of(postMapping.consumes(), postMapping.produces(), postMapping.value()));
            return map;
        }
        DeleteMapping deleteMapping = AnnotatedElementUtils.findMergedAnnotation(method, DeleteMapping.class);
        if (Objects.nonNull(deleteMapping)) {
            map.put(ApiHttpMethodEnum.DELETE, Triple.of(deleteMapping.consumes(), deleteMapping.produces(), deleteMapping.value()));
            return map;
        }
        PatchMapping patchMapping = AnnotatedElementUtils.findMergedAnnotation(method, PatchMapping.class);
        if (Objects.nonNull(patchMapping)) {
            map.put(ApiHttpMethodEnum.PATCH, Triple.of(patchMapping.consumes(), patchMapping.produces(), patchMapping.value()));
            return map;
        }
        return map;
    }

    @Override
    protected Map<String, Object> getBeans(final ApplicationContext context) {
        // Filter out is not controller out
        if (Boolean.TRUE.equals(isFull)) {
            getPublisher().publishEvent(MetaDataRegisterDTO.builder()
                    .contextPath(getContextPath())
                    .appName(getAppName())
                    .path(PathUtils.decoratorPathWithSlash(getContextPath()))
                    .rpcType(RpcTypeEnum.SPRING_CLOUD.getName())
                    .enabled(true)
                    .ruleName(getContextPath())
                    .build());
            return null;
        }
        return context.getBeansWithAnnotation(Controller.class);
    }

    @Override
    protected URIRegisterDTO buildURIRegisterDTO(final ApplicationContext context, final Map<String, Object> beans) {
        try {
            final String host = getHost();
            final int port = Integer.parseInt(Optional.ofNullable(getPort()).orElseGet(() -> "-1"));
            final int mergedPort = port <= 0 ? PortUtils.findPort(context.getAutowireCapableBeanFactory()) : port;
            return URIRegisterDTO.builder()
                    .contextPath(getContextPath())
                    .appName(getAppName())
                    .host(IpUtils.isCompleteHost(host) ? host : IpUtils.getHost(host))
                    .port(mergedPort)
                    .rpcType(RpcTypeEnum.SPRING_CLOUD.getName())
                    .build();
        } catch (ShenyuException e) {
            throw new ShenyuException(e.getMessage() + "please config ${shenyu.client.http.props.port} in xml/yml !");
        }
    }

    @Override
    protected void handleMethod(final Object bean, final Class<?> clazz,
                                @Nullable final ShenyuSpringCloudClient beanShenyuClient, final Method method,
                                final String superPath) {
        final RequestMapping requestMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        ShenyuSpringCloudClient methodShenyuClient = AnnotatedElementUtils.findMergedAnnotation(method, ShenyuSpringCloudClient.class);
        methodShenyuClient = Objects.isNull(methodShenyuClient) ? beanShenyuClient : methodShenyuClient;
        // the result of ReflectionUtils#getUniqueDeclaredMethods contains methods such as hashCode, wait, toSting
        // add Objects.nonNull(requestMapping) to make sure not register wrong method
        if (Objects.nonNull(methodShenyuClient) && Objects.nonNull(requestMapping)) {
            getPublisher().publishEvent(buildMetaDataDTO(bean, methodShenyuClient,
                    buildApiPath(method, superPath, methodShenyuClient), clazz, method));
        }
    }

    @Override
    protected String buildApiPath(final Method method, final String superPath,
                                  @NonNull final ShenyuSpringCloudClient methodShenyuClient) {
        final String contextPath = getContextPath();
        if (StringUtils.isNotBlank(methodShenyuClient.path())) {
            return pathJoin(contextPath, superPath, methodShenyuClient.path());
        }
        final String path = getPathByMethod(method);
        if (StringUtils.isNotBlank(path)) {
            return pathJoin(contextPath, superPath, path);
        }
        return pathJoin(contextPath, superPath);
    }
    
    private String getPathByMethod(@NonNull final Method method) {
        for (Class<? extends Annotation> mapping : mappingAnnotation) {
            final Annotation mergedAnnotation = AnnotatedElementUtils.findMergedAnnotation(method, mapping);
            final String pathByAnnotation = getPathByAnnotation(mergedAnnotation);
            if (StringUtils.isNotBlank(pathByAnnotation)) {
                return pathByAnnotation;
            }
        }
        return null;
    }
    
    private String getPathByAnnotation(@Nullable final Annotation annotation) {
        if (Objects.isNull(annotation)) {
            return null;
        }
        final Object value = AnnotationUtils.getValue(annotation, "value");
        if (value instanceof String && StringUtils.isNotBlank((String) value)) {
            return (String) value;
        }
        if (value instanceof String[] && ArrayUtils.isNotEmpty((String[]) value)
                && StringUtils.isNotBlank(((String[]) value)[0])) {
            return ((String[]) value)[0];
        }
        return null;
    }

    @Override
    protected String buildApiSuperPath(final Class<?> clazz, @Nullable final ShenyuSpringCloudClient beanShenyuClient) {
        if (Objects.nonNull(beanShenyuClient) && StringUtils.isNotBlank(beanShenyuClient.path())) {
            return beanShenyuClient.path();
        }
        RequestMapping requestMapping = AnnotationUtils.findAnnotation(clazz, RequestMapping.class);
        // Only the first path is supported temporarily
        if (Objects.nonNull(requestMapping) && ArrayUtils.isNotEmpty(requestMapping.path())
                && StringUtils.isNotBlank(requestMapping.path()[0])) {
            return requestMapping.path()[0];
        }
        return "";
    }

    @Override
    protected Class<ShenyuSpringCloudClient> getAnnotationType() {
        return ShenyuSpringCloudClient.class;
    }

    @Override
    protected MetaDataRegisterDTO buildMetaDataDTO(final Object bean,
                                                   final @NonNull ShenyuSpringCloudClient shenyuClient,
                                                   final String path, final Class<?> clazz, final Method method) {
        return MetaDataRegisterDTO.builder()
                .contextPath(StringUtils.defaultIfBlank(getContextPath(), this.servletContextPath))
                .addPrefixed(addPrefixed)
                .appName(getAppName())
                .serviceName(clazz.getName())
                .methodName(Optional.ofNullable(method).map(Method::getName).orElse(null))
                .path(path)
                .pathDesc(shenyuClient.desc())
                .parameterTypes(Optional.ofNullable(method)
                        .map(m -> Arrays.stream(m.getParameterTypes())
                                .map(Class::getName)
                                .collect(Collectors.joining(","))
                                ).orElse(null))
                .rpcType(RpcTypeEnum.SPRING_CLOUD.getName())
                .enabled(shenyuClient.enabled())
                .ruleName(StringUtils.defaultIfBlank(shenyuClient.ruleName(), path))
                .build();
    }

    @Override
    public String getAppName() {
        return env.getProperty("spring.application.name");
    }
}
