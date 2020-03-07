/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.handler;

import org.springframework.beans.BeansException;
import org.springframework.util.CollectionUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Implementation of the {@link org.springframework.web.servlet.HandlerMapping}
 * interface to map from URLs to request handler beans. Supports both mapping to bean
 * instances and mapping to bean names; the latter is required for non-singleton handlers.
 *
 * <p>The "urlMap" property is suitable for populating the handler map with
 * bean references, e.g. via the map element in XML bean definitions.
 *
 * <p>Mappings to bean names can be set via the "mappings" property, in a form
 * accepted by the {@code java.util.Properties} class, like as follows:<br>
 * {@code
 * /welcome.html=ticketController
 * /show.html=ticketController
 * }<br>
 * The syntax is {@code PATH=HANDLER_BEAN_NAME}.
 * If the path doesn't begin with a slash, one is prepended.
 *
 * <p>Supports direct matches (given "/test" -> registered "/test") and "*"
 * pattern matches (given "/test" -> registered "/t*"). Note that the default
 * is to map within the current servlet mapping if applicable; see the
 * {@link #setAlwaysUseFullPath "alwaysUseFullPath"} property. For details on the
 * pattern options, see the {@link org.springframework.util.AntPathMatcher} javadoc.

 处理实现Controller接口的处理器


 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #setMappings
 * @see #setUrlMap
 * @see BeanNameUrlHandlerMapping
 */
public class SimpleUrlHandlerMapping extends AbstractUrlHandlerMapping {

	//url与Controller的映射关系；这里的handler有可能是Controller的名称，不是实体Bean。
	//这里需要显式配置，在容器初始化时，解析配置并添加到urlMap缓存。
	private final Map<String, Object> urlMap = new LinkedHashMap<>();


	/**
	 * Map URL paths to handler bean names.
	 * This is the typical way of configuring this HandlerMapping.
	 * <p>Supports direct URL matches and Ant-style pattern matches. For syntax
	 * details, see the {@link org.springframework.util.AntPathMatcher} javadoc.
	 * @param mappings properties with URLs as keys and bean names as values
	 * @see #setUrlMap
	 */
	public void setMappings(Properties mappings) {
		CollectionUtils.mergePropertiesIntoMap(mappings, this.urlMap);
	}

	/**
	 * Set a Map with URL paths as keys and handler beans (or handler bean names)
	 * as values. Convenient for population with bean references.
	 * <p>Supports direct URL matches and Ant-style pattern matches. For syntax
	 * details, see the {@link org.springframework.util.AntPathMatcher} javadoc.
	 * @param urlMap map with URLs as keys and beans as values
	 * @see #setMappings
	 */
	public void setUrlMap(Map<String, ?> urlMap) {
		this.urlMap.putAll(urlMap);
	}

	/**
	 * Allow Map access to the URL path mappings, with the option to add or
	 * override specific entries.
	 * <p>Useful for specifying entries directly, for example via "urlMap[myKey]".
	 * This is particularly useful for adding or overriding entries in child
	 * bean definitions.
	 */
	public Map<String, ?> getUrlMap() {
		return this.urlMap;
	}


	/**
	 * Calls the {@link #registerHandlers} method in addition to the
	 * superclass's initialization.
	 */
	@Override
	public void initApplicationContext() throws BeansException {
		//注册HandlerMapping的所有拦截器（注册在AbstractHandlerMapping中），步骤如下：
		//1、从Servlet内部上下文中获取已注册的MappedInterceptor的对象，作为合适的拦截器，添加到合适拦截器
		//缓存adaptedInterceptors中
		//2、从父类AbstractHandlerMapping中的拦截器数组缓存中，遍历每一个拦截器，将其转化为HandlerInterceptor或
		//WebRequestHandlerInterceptor,添加到父类的合适拦截器缓存adaptedInterceptors中
		super.initApplicationContext();
		//向父类AbstractURLHandlerMapping注册当前handlerMap中的url与controller映射关系
		registerHandlers(this.urlMap);
	}

	/**
	 * Register all handlers specified in the URL map for the corresponding paths.
	 * @param urlMap Map with URL paths as keys and handler beans or bean names as values
	 * @throws BeansException if a handler couldn't be registered
	 * @throws IllegalStateException if there is a conflicting handler registered
	 */
	protected void registerHandlers(Map<String, Object> urlMap) throws BeansException {
		if (urlMap.isEmpty()) {
			logger.warn("Neither 'urlMap' nor 'mappings' set on SimpleUrlHandlerMapping");
		}
		else {
			urlMap.forEach((url, handler) -> {
				// Prepend with slash if not already present.
				//为每一个mapping的url，都添加一个正斜杠开头
				if (!url.startsWith("/")) {
					url = "/" + url;
				}
				// Remove whitespace from handler bean name.
				if (handler instanceof String) {
					handler = ((String) handler).trim();
				}
				//向父类AbstractURLHandlerMapping注册处理器。
				//注册过程：
				//1、如果当前的处理器对象是处理的名称，则从Servlet内部上下文中获取对应的处理器对象；
				//2、再从父类AbstractURLHandlerMapping的缓存handlerMap中获取URL对应的处理器对象；
				//3、比较从Servlet内部上下文容器中获取的处理器对象与缓存中的处理器对象，不等则异常；
				//4、相等则做以下判断确定当前处理器
				//	a、URL是"/"，则是根处理器，并在父类设置当前根处理器
				//	b、URL是"/*"，则是默认处理器，并在父类设置当前默认处理器
				//	c、URL是其他形式，则添加到父类的url映射处理对象缓存中，即添加到handlerMap中；
				//注意父类AbstractURLHandlerMapping的handlerMap的处理器对象与当前SimpleURLHandlerMapping的
				//handlerMap的处理器对象有区别，SimpleURLHandlerMapping的handlerMap的处理器对象可能是Controller
				//的名称，父类AbstractURLHandlerMapping的handlerMap的处理器一定是处理器对象。
				registerHandler(url, handler);
			});
		}
	}

}
