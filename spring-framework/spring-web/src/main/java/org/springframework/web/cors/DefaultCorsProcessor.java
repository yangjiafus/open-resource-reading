/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.web.cors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.web.util.WebUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The default implementation of {@link CorsProcessor}, as defined by the
 * <a href="http://www.w3.org/TR/cors/">CORS W3C recommendation</a>.
 *
 * <p>Note that when input {@link CorsConfiguration} is {@code null}, this
 * implementation does not reject simple or actual requests outright but simply
 * avoid adding CORS headers to the response. CORS processing is also skipped
 * if the response already contains CORS headers, or if the request is detected
 * as a same-origin one.
 *
 * @author Sebastien Deleuze
 * @author Rossen Stoyanchev
 * @since 4.2
 */
public class DefaultCorsProcessor implements CorsProcessor {

	private static final Log logger = LogFactory.getLog(DefaultCorsProcessor.class);


	@Override
	@SuppressWarnings("resource")
	public boolean processRequest(@Nullable CorsConfiguration config, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		//不是跨域请求，正常返回
		if (!CorsUtils.isCorsRequest(request)) {
			return true;
		}

		ServletServerHttpResponse serverResponse = new ServletServerHttpResponse(response);
		//获取响应response头中的 Access-Control-Allow-Origin（访问控制允许源地址，暂且这么直译） 属性，
		//如果有值则认为，服务（其实的跨域拦截器CorsInterceptor的DefaultCorsProcessor已处理跨域了）
		//已经进行了跨域处理，当前跨域处理器直接返回。
		if (responseHasCors(serverResponse)) {
			logger.debug("Skip CORS processing: response already contains \"Access-Control-Allow-Origin\" header");
			return true;
		}

		ServletServerHttpRequest serverRequest = new ServletServerHttpRequest(request);
		//同源策略：
		//协议、域名和端口都保持一致的请求，才认为是同源，任何一个不一样都不认为是同源。
		//根据同源策略判断请求与服务是否同源，是同源请求，则不做跨域请求处理
		if (WebUtils.isSameOrigin(serverRequest)) {
			logger.debug("Skip CORS processing: request is from same origin");
			return true;
		}
		//判断当前请求是否是预请求 OPTIONS（HTTP请求的一种方式）,判断标准：
		//1、http请求方法为 OPTIONS
		//2、请求头的Access-Control-Request-Method属性必须有值
		//3、当前请求必须是跨域请求，即Origin属性必有有值
		//三者不可缺一
		boolean preFlightRequest = CorsUtils.isPreFlightRequest(request);
		if (config == null) {
			//在未配置允许跨域访问时，跨域请求的预请求不进行跨域处理，返回false表示请求处理失败。
			//响应response状态码标记为 403，并返回body：Invalid CORS request
			if (preFlightRequest) {
				rejectRequest(serverResponse);
				return false;
			}
			else {
			//在未配置允许跨域访问时，跨域请求不进行跨域处理，返回true表示处理完毕；
			//TODO 避免有的浏览器对于跨域请求不发起预请求，而直接发起真实的跨域请求。
				return true;
			}
		}
		//跨域请求权限验证
		return handleInternal(serverRequest, serverResponse, config, preFlightRequest);
	}

	private boolean responseHasCors(ServerHttpResponse response) {
		try {
			return (response.getHeaders().getAccessControlAllowOrigin() != null);
		}
		catch (NullPointerException npe) {
			// SPR-11919 and https://issues.jboss.org/browse/WFLY-3474
			return false;
		}
	}

	/**
	 * Invoked when one of the CORS checks failed.
	 * The default implementation sets the response status to 403 and writes
	 * "Invalid CORS request" to the response.
	 */
	protected void rejectRequest(ServerHttpResponse response) throws IOException {
		response.setStatusCode(HttpStatus.FORBIDDEN);
		response.getBody().write("Invalid CORS request".getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Handle the given request.
	 */
	protected boolean handleInternal(ServerHttpRequest request, ServerHttpResponse response,
			CorsConfiguration config, boolean preFlightRequest) throws IOException {
		//获取发起请求的源地址
		String requestOrigin = request.getHeaders().getOrigin();
		//检查发请求的源地址是否在允许跨域配置范围内，检查结果为 null 表示不允许跨域访问。
		String allowOrigin = checkOrigin(config, requestOrigin);
		HttpHeaders responseHeaders = response.getHeaders();
		//添加跨域处理的响应头属性：
		//Origin
		//Access-Control-Request-Method
		//Access-Control-Request-Headers
		//后面跨域请求/预请求权限验证完毕后会添加 Access-Control-Allow-Origin
		responseHeaders.addAll(HttpHeaders.VARY, Arrays.asList(HttpHeaders.ORIGIN,
				HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS));

		//没有匹配到跨域的配置项，则认为当前无权访问
		if (allowOrigin == null) {
			logger.debug("Rejecting CORS request because '" + requestOrigin + "' origin is not allowed");
			rejectRequest(response);
			return false;
		}
		//获取当前跨域请求的请求方式：OPTION GET POST 等
		HttpMethod requestMethod = getMethodToUse(request, preFlightRequest);
		//匹配当前请求允许的请求方式，并返回匹配成功的请求方式
		List<HttpMethod> allowMethods = checkMethods(config, requestMethod);
		//没有匹配到允许访问的请求方式，拒绝访问
		if (allowMethods == null) {
			logger.debug("Rejecting CORS request because '" + requestMethod + "' request method is not allowed");
			rejectRequest(response);
			return false;
		}
		//获取当前跨域请求的请求头信息
		List<String> requestHeaders = getHeadersToUse(request, preFlightRequest);
		//匹配允许的请求头信息，并返回匹配成功的请求头信息
		List<String> allowHeaders = checkHeaders(config, requestHeaders);
		//如果当前是预请求且没有匹配到允许的头信息，则拒绝访问
		if (preFlightRequest && allowHeaders == null) {
			logger.debug("Rejecting CORS request because '" + requestHeaders + "' request headers are not allowed");
			rejectRequest(response);
			return false;
		}

		//跨域请求或跨域预请求权限验证完毕，设置访问控制允许源地址
		responseHeaders.setAccessControlAllowOrigin(allowOrigin);

		//设置访问控制允许方法
		if (preFlightRequest) {
			responseHeaders.setAccessControlAllowMethods(allowMethods);
		}
		//设置访问控制允许头
		if (preFlightRequest && !allowHeaders.isEmpty()) {
			responseHeaders.setAccessControlAllowHeaders(allowHeaders);
		}

		//设置访问控制允许方法
		if (!CollectionUtils.isEmpty(config.getExposedHeaders())) {
			responseHeaders.setAccessControlExposeHeaders(config.getExposedHeaders());
		}

		//设置访问控制允许证书
		if (Boolean.TRUE.equals(config.getAllowCredentials())) {
			responseHeaders.setAccessControlAllowCredentials(true);
		}
		//设置访问控制最大大小
		if (preFlightRequest && config.getMaxAge() != null) {
			responseHeaders.setAccessControlMaxAge(config.getMaxAge());
		}

		response.flush();
		return true;
	}

	/**
	 * Check the origin and determine the origin for the response. The default
	 * implementation simply delegates to
	 * {@link org.springframework.web.cors.CorsConfiguration#checkOrigin(String)}.
	 */
	@Nullable
	protected String checkOrigin(CorsConfiguration config, @Nullable String requestOrigin) {
		return config.checkOrigin(requestOrigin);
	}

	/**
	 * Check the HTTP method and determine the methods for the response of a
	 * pre-flight request. The default implementation simply delegates to
	 * {@link org.springframework.web.cors.CorsConfiguration#checkOrigin(String)}.
	 */
	@Nullable
	protected List<HttpMethod> checkMethods(CorsConfiguration config, @Nullable HttpMethod requestMethod) {
		return config.checkHttpMethod(requestMethod);
	}

	@Nullable
	private HttpMethod getMethodToUse(ServerHttpRequest request, boolean isPreFlight) {
		return (isPreFlight ? request.getHeaders().getAccessControlRequestMethod() : request.getMethod());
	}

	/**
	 * Check the headers and determine the headers for the response of a
	 * pre-flight request. The default implementation simply delegates to
	 * {@link org.springframework.web.cors.CorsConfiguration#checkOrigin(String)}.
	 */
	@Nullable
	protected List<String> checkHeaders(CorsConfiguration config, List<String> requestHeaders) {
		return config.checkHeaders(requestHeaders);
	}

	private List<String> getHeadersToUse(ServerHttpRequest request, boolean isPreFlight) {
		HttpHeaders headers = request.getHeaders();
		return (isPreFlight ? headers.getAccessControlRequestHeaders() : new ArrayList<>(headers.keySet()));
	}

}
