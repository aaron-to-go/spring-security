/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.config.annotation.web.configurers;

import java.util.function.Supplier;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.TestAuthentication;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.MockServletContext;
import org.springframework.security.config.TestMockHttpServletMappings;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.AbstractRequestMatcherRegistry;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.config.test.SpringTestContext;
import org.springframework.security.config.test.SpringTestContextExtension;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager;
import org.springframework.security.web.servlet.util.matcher.MvcRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link AuthorizeHttpRequestsConfigurer}.
 *
 * @author Evgeniy Cheban
 */
@ExtendWith(SpringTestContextExtension.class)
public class AuthorizeHttpRequestsConfigurerTests {

	public final SpringTestContext spring = new SpringTestContext(this);

	@Autowired
	MockMvc mvc;

	@Test
	public void configureWhenAuthorizedHttpRequestsAndNoRequestsThenException() {
		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> this.spring.register(NoRequestsConfig.class).autowire())
			.withMessageContaining(
					"At least one mapping is required (for example, authorizeHttpRequests().anyRequest().authenticated())");
	}

	@Test
	public void configureNoParameterWhenAuthorizedHttpRequestsAndNoRequestsThenException() {
		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> this.spring.register(NoRequestsNoParameterConfig.class).autowire())
			.withMessageContaining(
					"At least one mapping is required (for example, authorizeHttpRequests().anyRequest().authenticated())");
	}

	@Test
	public void configureWhenAnyRequestIncompleteMappingThenException() {
		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> this.spring.register(IncompleteMappingConfig.class).autowire())
			.withMessageContaining("An incomplete mapping was found for ");
	}

	@Test
	public void configureNoParameterWhenAnyRequestIncompleteMappingThenException() {
		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> this.spring.register(IncompleteMappingNoParameterConfig.class).autowire())
			.withMessageContaining("An incomplete mapping was found for ");
	}

	@Test
	public void configureWhenMvcMatcherAfterAnyRequestThenException() {
		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> this.spring.register(AfterAnyRequestConfig.class).autowire())
			.withMessageContaining("Can't configure requestMatchers after anyRequest");
	}

	@Test
	public void configureMvcMatcherAccessAuthorizationManagerWhenNotNullThenVerifyUse() throws Exception {
		CustomAuthorizationManagerConfig.authorizationManager = mock(AuthorizationManager.class);
		this.spring.register(CustomAuthorizationManagerConfig.class, BasicController.class).autowire();
		this.mvc.perform(get("/")).andExpect(status().isOk());
		verify(CustomAuthorizationManagerConfig.authorizationManager).check(any(), any());
	}

	@Test
	public void configureNoParameterMvcMatcherAccessAuthorizationManagerWhenNotNullThenVerifyUse() throws Exception {
		CustomAuthorizationManagerNoParameterConfig.authorizationManager = mock(AuthorizationManager.class);
		this.spring.register(CustomAuthorizationManagerNoParameterConfig.class, BasicController.class).autowire();
		this.mvc.perform(get("/")).andExpect(status().isOk());
		verify(CustomAuthorizationManagerNoParameterConfig.authorizationManager).check(any(), any());
	}

	@Test
	public void configureMvcMatcherAccessAuthorizationManagerWhenNullThenException() {
		CustomAuthorizationManagerConfig.authorizationManager = null;
		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> this.spring.register(CustomAuthorizationManagerConfig.class).autowire())
			.withMessageContaining("manager cannot be null");
	}

	@Test
	public void configureWhenObjectPostProcessorRegisteredThenInvokedOnAuthorizationManagerAndAuthorizationFilter() {
		this.spring.register(ObjectPostProcessorConfig.class).autowire();
		ObjectPostProcessor objectPostProcessor = this.spring.getContext().getBean(ObjectPostProcessor.class);
		verify(objectPostProcessor).postProcess(any(RequestMatcherDelegatingAuthorizationManager.class));
		verify(objectPostProcessor).postProcess(any(AuthorizationFilter.class));
	}

	@Test
	public void getWhenHasAnyAuthorityRoleUserConfiguredAndAuthorityIsRoleUserThenRespondsWithOk() throws Exception {
		this.spring.register(RoleUserAnyAuthorityConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
				.authorities(new SimpleGrantedAuthority("ROLE_USER")));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isOk());
	}

	@Test
	public void getWhenHasAnyAuthorityRoleUserConfiguredAndAuthorityIsRoleAdminThenRespondsWithForbidden()
			throws Exception {
		this.spring.register(RoleUserAnyAuthorityConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithAdmin = get("/")
				.with(user("user")
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
		// @formatter:on
		this.mvc.perform(requestWithAdmin).andExpect(status().isForbidden());
	}

	@Test
	public void getWhenHasAnyAuthorityRoleUserConfiguredAndNoAuthorityThenRespondsWithUnauthorized() throws Exception {
		this.spring.register(RoleUserAnyAuthorityConfig.class, BasicController.class).autowire();
		this.mvc.perform(get("/")).andExpect(status().isUnauthorized());
	}

	@Test
	public void getWhenHasAuthorityRoleUserConfiguredAndAuthorityIsRoleUserThenRespondsWithOk() throws Exception {
		this.spring.register(RoleUserAuthorityConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
				.authorities(new SimpleGrantedAuthority("ROLE_USER")));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isOk());
	}

	@Test
	public void getWhenHasAuthorityRoleUserConfiguredAndAuthorityIsRoleAdminThenRespondsWithForbidden()
			throws Exception {
		this.spring.register(RoleUserAuthorityConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithAdmin = get("/")
				.with(user("user")
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
		// @formatter:on
		this.mvc.perform(requestWithAdmin).andExpect(status().isForbidden());
	}

	@Test
	public void getWhenHasAuthorityRoleUserConfiguredAndNoAuthorityThenRespondsWithUnauthorized() throws Exception {
		this.spring.register(RoleUserAuthorityConfig.class, BasicController.class).autowire();
		this.mvc.perform(get("/")).andExpect(status().isUnauthorized());
	}

	@Test
	public void getWhenAuthorityRoleUserOrAdminRequiredAndAuthorityIsRoleUserThenRespondsWithOk() throws Exception {
		this.spring.register(RoleUserOrRoleAdminAuthorityConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
				.authorities(new SimpleGrantedAuthority("ROLE_USER")));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isOk());
	}

	@Test
	public void getWhenAuthorityRoleUserOrAdminRequiredAndAuthorityIsRoleAdminThenRespondsWithOk() throws Exception {
		this.spring.register(RoleUserOrRoleAdminAuthorityConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithAdmin = get("/")
				.with(user("user")
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
		// @formatter:on
		this.mvc.perform(requestWithAdmin).andExpect(status().isOk());
	}

	@Test
	public void getWhenAuthorityRoleUserOrAdminRequiredAndAuthorityIsRoleOtherThenRespondsWithForbidden()
			throws Exception {
		this.spring.register(RoleUserOrRoleAdminAuthorityConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithOther = get("/")
				.with(user("user")
				.authorities(new SimpleGrantedAuthority("ROLE_OTHER")));
		// @formatter:on
		this.mvc.perform(requestWithOther).andExpect(status().isForbidden());
	}

	@Test
	public void getWhenAuthorityRoleUserOrAdminAuthRequiredAndNoUserThenRespondsWithUnauthorized() throws Exception {
		this.spring.register(RoleUserOrRoleAdminAuthorityConfig.class, BasicController.class).autowire();
		this.mvc.perform(get("/")).andExpect(status().isUnauthorized());
	}

	@Test
	public void getWhenHasRoleUserConfiguredAndRoleIsUserThenRespondsWithOk() throws Exception {
		this.spring.register(RoleUserConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
				.roles("USER"));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isOk());
	}

	@Test
	public void getWhenHasRoleUserConfiguredAndRoleIsAdminThenRespondsWithForbidden() throws Exception {
		this.spring.register(RoleUserConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithAdmin = get("/")
				.with(user("user")
				.roles("ADMIN"));
		// @formatter:on
		this.mvc.perform(requestWithAdmin).andExpect(status().isForbidden());
	}

	@Test
	public void getWhenHasRoleUserAndRoleHierarchyConfiguredThenGreaterRoleTakesPrecedence() throws Exception {
		this.spring.register(RoleHierarchyUserConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithAdmin = get("/")
				.with(user("user")
				.roles("ADMIN"));
		// @formatter:on
		this.mvc.perform(requestWithAdmin).andExpect(status().isOk());
	}

	@Test
	public void getWhenRoleUserOrAdminConfiguredAndRoleIsUserThenRespondsWithOk() throws Exception {
		this.spring.register(RoleUserOrAdminConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
				.roles("USER"));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isOk());
	}

	@Test
	public void getWhenRoleUserOrAdminConfiguredAndRoleIsAdminThenRespondsWithOk() throws Exception {
		this.spring.register(RoleUserOrAdminConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithAdmin = get("/")
				.with(user("user")
				.roles("ADMIN"));
		// @formatter:on
		this.mvc.perform(requestWithAdmin).andExpect(status().isOk());
	}

	@Test
	public void getWhenRoleUserOrAdminConfiguredAndRoleIsOtherThenRespondsWithForbidden() throws Exception {
		this.spring.register(RoleUserOrAdminConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithRoleOther = get("/")
				.with(user("user")
				.roles("OTHER"));
		// @formatter:on
		this.mvc.perform(requestWithRoleOther).andExpect(status().isForbidden());
	}

	@Test
	public void getWhenDenyAllConfiguredAndNoUserThenRespondsWithUnauthorized() throws Exception {
		this.spring.register(DenyAllConfig.class, BasicController.class).autowire();
		this.mvc.perform(get("/")).andExpect(status().isUnauthorized());
	}

	@Test
	public void getWhenDenyAllConfiguredAndUserLoggedInThenRespondsWithForbidden() throws Exception {
		this.spring.register(DenyAllConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
				.roles("USER"));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isForbidden());
	}

	@Test
	public void getWhenPermitAllConfiguredAndNoUserThenRespondsWithOk() throws Exception {
		this.spring.register(PermitAllConfig.class, BasicController.class).autowire();
		this.mvc.perform(get("/")).andExpect(status().isOk());
	}

	@Test
	public void getWhenPermitAllConfiguredAndUserLoggedInThenRespondsWithOk() throws Exception {
		this.spring.register(PermitAllConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
				.roles("USER"));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isOk());
	}

	@Test
	public void authorizeHttpRequestsWhenInvokedTwiceThenUsesOriginalConfiguration() throws Exception {
		this.spring.register(InvokeTwiceDoesNotResetConfig.class, BasicController.class).autowire();
		this.mvc.perform(post("/").with(csrf())).andExpect(status().isUnauthorized());
	}

	@Test
	public void getWhenServletPathRoleAdminConfiguredAndRoleIsUserThenRespondsWithForbidden() throws Exception {
		this.spring.register(MvcServletPathConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/spring/")
				.servletPath("/spring")
				.with(user("user")
				.roles("USER"));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isForbidden());
	}

	@Test
	public void getWhenServletPathRoleAdminConfiguredAndRoleIsUserAndWithoutServletPathThenRespondsWithForbidden()
			throws Exception {
		this.spring.register(MvcServletPathConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
				.roles("USER"));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isForbidden());
	}

	@Test
	public void getWhenServletPathRoleAdminConfiguredAndRoleIsAdminThenRespondsWithOk() throws Exception {
		this.spring.register(MvcServletPathConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithAdmin = get("/spring/")
				.servletPath("/spring")
				.with(user("user")
				.roles("ADMIN"));
		// @formatter:on
		this.mvc.perform(requestWithAdmin).andExpect(status().isOk());
	}

	@Test
	public void getWhenAnyRequestAuthenticatedConfiguredAndNoUserThenRespondsWithUnauthorized() throws Exception {
		this.spring.register(AuthenticatedConfig.class, BasicController.class).autowire();
		this.mvc.perform(get("/")).andExpect(status().isUnauthorized());
	}

	@Test
	public void getWhenCustomAuthorizationEventPublisherThenUses() throws Exception {
		this.spring.register(AuthenticatedConfig.class, AuthorizationEventPublisherConfig.class).autowire();
		this.mvc.perform(get("/")).andExpect(status().isUnauthorized());
		AuthorizationEventPublisher publisher = this.spring.getContext().getBean(AuthorizationEventPublisher.class);
		verify(publisher).publishAuthorizationEvent(any(Supplier.class), any(HttpServletRequest.class),
				any(AuthorizationDecision.class));
	}

	@Test
	public void getWhenAnyRequestAuthenticatedConfiguredAndUserLoggedInThenRespondsWithOk() throws Exception {
		this.spring.register(AuthenticatedConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
				.roles("USER"));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isOk());
	}

	@Test
	public void getWhenExpressionHasRoleUserConfiguredAndRoleIsUserThenRespondsWithOk() throws Exception {
		this.spring.register(ExpressionRoleUserConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
				.roles("USER"));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isOk());
	}

	@Test
	public void getWhenExpressionHasRoleUserConfiguredAndRoleIsAdminThenRespondsWithForbidden() throws Exception {
		this.spring.register(ExpressionRoleUserConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithAdmin = get("/")
				.with(user("user")
				.roles("ADMIN"));
		// @formatter:on
		this.mvc.perform(requestWithAdmin).andExpect(status().isForbidden());
	}

	@Test
	public void getWhenExpressionRoleUserOrAdminConfiguredAndRoleIsUserThenRespondsWithOk() throws Exception {
		this.spring.register(ExpressionRoleUserOrAdminConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
				.roles("USER"));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isOk());
	}

	@Test
	public void getWhenExpressionRoleUserOrAdminConfiguredAndRoleIsAdminThenRespondsWithOk() throws Exception {
		this.spring.register(ExpressionRoleUserOrAdminConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithAdmin = get("/")
				.with(user("user")
				.roles("ADMIN"));
		// @formatter:on
		this.mvc.perform(requestWithAdmin).andExpect(status().isOk());
	}

	@Test
	public void getWhenExpressionRoleUserOrAdminConfiguredAndRoleIsOtherThenRespondsWithForbidden() throws Exception {
		this.spring.register(ExpressionRoleUserOrAdminConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithRoleOther = get("/")
				.with(user("user")
				.roles("OTHER"));
		// @formatter:on
		this.mvc.perform(requestWithRoleOther).andExpect(status().isForbidden());
	}

	@Test
	public void getWhenCustomRolePrefixAndRoleHasDifferentPrefixThenRespondsWithForbidden() throws Exception {
		this.spring.register(GrantedAuthorityDefaultHasRoleConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
						.authorities(new SimpleGrantedAuthority("ROLE_USER")));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isForbidden());
	}

	@Test
	public void getWhenCustomRolePrefixAndHasRoleThenRespondsWithOk() throws Exception {
		this.spring.register(GrantedAuthorityDefaultHasRoleConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
						.authorities(new SimpleGrantedAuthority("CUSTOM_PREFIX_USER")));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isOk());
	}

	@Test
	public void getWhenCustomRolePrefixAndHasAnyRoleThenRespondsWithOk() throws Exception {
		this.spring.register(GrantedAuthorityDefaultHasAnyRoleConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestWithUser = get("/")
				.with(user("user")
						.authorities(new SimpleGrantedAuthority("CUSTOM_PREFIX_USER")));
		MockHttpServletRequestBuilder requestWithAdmin = get("/")
				.with(user("user")
						.authorities(new SimpleGrantedAuthority("CUSTOM_PREFIX_ADMIN")));
		// @formatter:on
		this.mvc.perform(requestWithUser).andExpect(status().isOk());
		this.mvc.perform(requestWithAdmin).andExpect(status().isOk());
	}

	@Test
	public void getWhenExpressionHasIpAddressLocalhostConfiguredIpAddressIsLocalhostThenRespondsWithOk()
			throws Exception {
		this.spring.register(ExpressionIpAddressLocalhostConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestFromLocalhost = get("/")
				.with(remoteAddress("127.0.0.1"));
		// @formatter:on
		this.mvc.perform(requestFromLocalhost).andExpect(status().isOk());
	}

	@Test
	public void getWhenExpressionHasIpAddressLocalhostConfiguredIpAddressIsOtherThenRespondsWithForbidden()
			throws Exception {
		this.spring.register(ExpressionIpAddressLocalhostConfig.class, BasicController.class).autowire();
		// @formatter:off
		MockHttpServletRequestBuilder requestFromOtherHost = get("/")
				.with(remoteAddress("192.168.0.1"));
		// @formatter:on
		this.mvc.perform(requestFromOtherHost).andExpect(status().isForbidden());
	}

	@Test
	public void requestWhenMvcMatcherPathVariablesThenMatchesOnPathVariables() throws Exception {
		this.spring.register(MvcMatcherPathVariablesInLambdaConfig.class).autowire();
		MockHttpServletRequestBuilder request = get("/user/user");
		this.mvc.perform(request).andExpect(status().isOk());
		request = get("/user/deny");
		this.mvc.perform(request).andExpect(status().isUnauthorized());
	}

	private static RequestPostProcessor remoteAddress(String remoteAddress) {
		return (request) -> {
			request.setRemoteAddr(remoteAddress);
			return request;
		};
	}

	@Test
	public void getWhenFullyAuthenticatedConfiguredAndRememberMeTokenThenRespondsWithUnauthorized() throws Exception {
		this.spring.register(FullyAuthenticatedConfig.class, BasicController.class).autowire();
		RememberMeAuthenticationToken rememberMe = new RememberMeAuthenticationToken("key", "user",
				AuthorityUtils.createAuthorityList("ROLE_USER"));
		MockHttpServletRequestBuilder requestWithRememberMe = get("/").with(authentication(rememberMe));
		this.mvc.perform(requestWithRememberMe).andExpect(status().isUnauthorized());
	}

	@Test
	public void getWhenFullyAuthenticatedConfiguredAndUserThenRespondsWithOk() throws Exception {
		this.spring.register(FullyAuthenticatedConfig.class, BasicController.class).autowire();
		MockHttpServletRequestBuilder requestWithUser = get("/").with(user("user").roles("USER"));
		this.mvc.perform(requestWithUser).andExpect(status().isOk());
	}

	@Test
	public void getWhenRememberMeConfiguredAndNoUserThenRespondsWithUnauthorized() throws Exception {
		this.spring.register(RememberMeConfig.class, BasicController.class).autowire();
		this.mvc.perform(get("/")).andExpect(status().isUnauthorized());
	}

	@Test
	public void getWhenRememberMeConfiguredAndRememberMeTokenThenRespondsWithOk() throws Exception {
		this.spring.register(RememberMeConfig.class, BasicController.class).autowire();
		RememberMeAuthenticationToken rememberMe = new RememberMeAuthenticationToken("key", "user",
				AuthorityUtils.createAuthorityList("ROLE_USER"));
		MockHttpServletRequestBuilder requestWithRememberMe = get("/").with(authentication(rememberMe));
		this.mvc.perform(requestWithRememberMe).andExpect(status().isOk());
	}

	@Test
	public void getWhenAnonymousConfiguredAndAnonymousUserThenRespondsWithOk() throws Exception {
		this.spring.register(AnonymousConfig.class, BasicController.class).autowire();
		this.mvc.perform(get("/")).andExpect(status().isOk());
	}

	@Test
	public void getWhenAnonymousConfiguredAndLoggedInUserThenRespondsWithForbidden() throws Exception {
		this.spring.register(AnonymousConfig.class, BasicController.class).autowire();
		MockHttpServletRequestBuilder requestWithUser = get("/").with(user("user"));
		this.mvc.perform(requestWithUser).andExpect(status().isForbidden());
	}

	@Test
	public void configureWhenNoDispatcherServletThenSucceeds() throws Exception {
		MockServletContext servletContext = new MockServletContext();
		servletContext.addServlet("default", Servlet.class).addMapping("/");
		this.spring.register(AuthorizeHttpRequestsConfig.class)
			.postProcessor((context) -> context.setServletContext(servletContext))
			.autowire();
		this.mvc.perform(get("/path")).andExpect(status().isNotFound());
	}

	@Test
	public void configureWhenOnlyDispatcherServletThenSucceeds() throws Exception {
		MockServletContext servletContext = new MockServletContext();
		servletContext.addServlet("dispatcherServlet", DispatcherServlet.class).addMapping("/mvc/*");
		this.spring.register(AuthorizeHttpRequestsConfig.class)
			.postProcessor((context) -> context.setServletContext(servletContext))
			.autowire();
		this.mvc.perform(get("/mvc/path").servletPath("/mvc")).andExpect(status().isNotFound());
		this.mvc.perform(get("/mvc")).andExpect(status().isUnauthorized());
	}

	@Test
	public void configureWhenMultipleServletsThenSucceeds() throws Exception {
		MockServletContext servletContext = MockServletContext.mvc();
		servletContext.addServlet("path", Servlet.class).addMapping("/path/*");
		this.spring.register(AuthorizeHttpRequestsConfig.class)
			.postProcessor((context) -> context.setServletContext(servletContext))
			.autowire();
		this.mvc.perform(get("/path").with(servletPath("/path"))).andExpect(status().isNotFound());
	}

	@Test
	public void configureWhenAmbiguousServletsThenWiringException() {
		MockServletContext servletContext = new MockServletContext();
		servletContext.addServlet("dispatcherServlet", DispatcherServlet.class).addMapping("/mvc/*");
		servletContext.addServlet("path", Servlet.class).addMapping("/path/*");
		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> this.spring.register(AuthorizeHttpRequestsConfig.class)
				.postProcessor((context) -> context.setServletContext(servletContext))
				.autowire());
	}

	@Test
	void defaultServletMatchersWhenDefaultServletThenPermits() throws Exception {
		this.spring.register(DefaultServletConfig.class)
			.postProcessor((context) -> context.setServletContext(MockServletContext.mvc()))
			.autowire();
		this.mvc.perform(get("/path/path").with(defaultServlet())).andExpect(status().isNotFound());
		this.mvc.perform(get("/path/path").with(servletPath("/path"))).andExpect(status().isUnauthorized());
	}

	@Test
	void defaultServletHttpMethodMatchersWhenDefaultServletThenPermits() throws Exception {
		this.spring.register(DefaultServletConfig.class)
			.postProcessor((context) -> context.setServletContext(MockServletContext.mvc()))
			.autowire();
		this.mvc.perform(get("/path/method").with(defaultServlet())).andExpect(status().isNotFound());
		this.mvc.perform(head("/path/method").with(defaultServlet())).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/path/method").with(servletPath("/path"))).andExpect(status().isUnauthorized());
	}

	@Test
	void defaultServletWhenNoDefaultServletThenWiringException() {
		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> this.spring.register(DefaultServletConfig.class)
				.postProcessor((context) -> context.setServletContext(new MockServletContext()))
				.autowire());
	}

	@Test
	void servletPathMatchersWhenMatchingServletThenPermits() throws Exception {
		MockServletContext servletContext = MockServletContext.mvc();
		servletContext.addServlet("path", Servlet.class).addMapping("/path/*");
		this.spring.register(ServletPathConfig.class)
			.postProcessor((context) -> context.setServletContext(servletContext))
			.autowire();
		this.mvc.perform(get("/path/path").with(servletPath("/path"))).andExpect(status().isNotFound());
		this.mvc.perform(get("/path/path").with(defaultServlet())).andExpect(status().isUnauthorized());
	}

	@Test
	void servletPathHttpMethodMatchersWhenMatchingServletThenPermits() throws Exception {
		MockServletContext servletContext = MockServletContext.mvc();
		servletContext.addServlet("path", Servlet.class).addMapping("/path/*");
		this.spring.register(ServletPathConfig.class)
			.postProcessor((context) -> context.setServletContext(servletContext))
			.autowire();
		this.mvc.perform(get("/path/method").with(servletPath("/path"))).andExpect(status().isNotFound());
		this.mvc.perform(head("/path/method").with(servletPath("/path"))).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/path/method").with(defaultServlet())).andExpect(status().isUnauthorized());
	}

	@Test
	void servletPathWhenNoMatchingPathThenWiringException() {
		MockServletContext servletContext = MockServletContext.mvc();
		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> this.spring.register(ServletPathConfig.class)
				.postProcessor((context) -> context.setServletContext(servletContext))
				.autowire());
	}

	@Test
	void servletMappingMatchersWhenMatchingServletThenPermits() throws Exception {
		MockServletContext servletContext = MockServletContext.mvc();
		servletContext.addServlet("jsp", Servlet.class).addMapping("*.jsp");
		this.spring.register(ServletMappingConfig.class)
			.postProcessor((context) -> context.setServletContext(servletContext))
			.autowire();
		this.mvc.perform(get("/path/file.jsp").with(servletExtension(".jsp"))).andExpect(status().isNotFound());
		this.mvc.perform(get("/path/file.jsp").with(defaultServlet())).andExpect(status().isUnauthorized());
	}

	@Test
	void servletMappingHttpMethodMatchersWhenMatchingServletThenPermits() throws Exception {
		MockServletContext servletContext = MockServletContext.mvc();
		servletContext.addServlet("jsp", Servlet.class).addMapping("*.jsp");
		this.spring.register(ServletMappingConfig.class)
			.postProcessor((context) -> context.setServletContext(servletContext))
			.autowire();
		this.mvc.perform(get("/method/file.jsp").with(servletExtension(".jsp"))).andExpect(status().isNotFound());
		this.mvc.perform(head("/method/file.jsp").with(servletExtension(".jsp"))).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/method/file.jsp").with(defaultServlet())).andExpect(status().isUnauthorized());
	}

	@Test
	void servletMappingWhenNoMatchingExtensionThenWiringException() {
		MockServletContext servletContext = MockServletContext.mvc();
		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> this.spring.register(ServletMappingConfig.class)
				.postProcessor((context) -> context.setServletContext(servletContext))
				.autowire());
	}

	@Test
	void anyRequestWhenUsedWithDefaultServletThenDoesNotWire() {
		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> this.spring.register(MixedServletEndpointConfig.class).autowire())
			.withMessageContaining("forServletPattern");
	}

	@Test
	void servletWhenNoMatchingPathThenDenies() throws Exception {
		MockServletContext servletContext = new MockServletContext();
		servletContext.addServlet("default", Servlet.class).addMapping("/");
		servletContext.addServlet("jspServlet", Servlet.class).addMapping("*.jsp");
		servletContext.addServlet("dispatcherServlet", DispatcherServlet.class).addMapping("/mvc/*");
		this.spring.register(DefaultServletAndServletPathConfig.class)
			.postProcessor((context) -> context.setServletContext(servletContext))
			.autowire();
		this.mvc.perform(get("/js/color.js").with(servletPath("/js"))).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/mvc/controller").with(defaultServlet())).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/js/color.js").with(defaultServlet())).andExpect(status().isNotFound());
		this.mvc.perform(get("/mvc/controller").with(servletPath("/mvc"))).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/mvc/controller").with(user("user")).with(servletPath("/mvc")))
			.andExpect(status().isNotFound());
	}

	@Test
	void permitAllWhenDefaultServletThenDoesNotWire() {
		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> this.spring.register(MixedServletPermitAllConfig.class).autowire())
			.withMessageContaining("forServletPattern");
	}

	static RequestPostProcessor defaultServlet() {
		return (request) -> {
			String uri = request.getRequestURI();
			request.setHttpServletMapping(TestMockHttpServletMappings.defaultMapping());
			request.setServletPath(uri);
			request.setPathInfo("");
			return request;
		};
	}

	static RequestPostProcessor servletPath(String path) {
		return (request) -> {
			String uri = request.getRequestURI();
			request.setHttpServletMapping(TestMockHttpServletMappings.path(request, path));
			request.setServletPath(path);
			request.setPathInfo(uri.substring(path.length()));
			return request;
		};
	}

	static RequestPostProcessor servletExtension(String extension) {
		return (request) -> {
			String uri = request.getRequestURI();
			request.setHttpServletMapping(TestMockHttpServletMappings.extension(request, extension));
			request.setServletPath(uri);
			request.setPathInfo("");
			return request;
		};
	}

	@Configuration
	@EnableWebSecurity
	static class GrantedAuthorityDefaultHasRoleConfig {

		@Bean
		GrantedAuthorityDefaults grantedAuthorityDefaults() {
			return new GrantedAuthorityDefaults("CUSTOM_PREFIX_");
		}

		@Bean
		SecurityFilterChain myFilterChain(HttpSecurity http) throws Exception {
			return http.authorizeHttpRequests((c) -> c.anyRequest().hasRole("USER")).build();
		}

	}

	@Configuration
	@EnableWebSecurity
	static class GrantedAuthorityDefaultHasAnyRoleConfig {

		@Bean
		GrantedAuthorityDefaults grantedAuthorityDefaults() {
			return new GrantedAuthorityDefaults("CUSTOM_PREFIX_");
		}

		@Bean
		SecurityFilterChain myFilterChain(HttpSecurity http) throws Exception {
			return http.authorizeHttpRequests((c) -> c.anyRequest().hasAnyRole("USER", "ADMIN")).build();
		}

	}

	@Configuration
	@EnableWebSecurity
	static class NoRequestsConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.authorizeHttpRequests(withDefaults())
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class NoRequestsNoParameterConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.authorizeHttpRequests();
			// @formatter:on

			return http.build();
		}

	}

	@Configuration
	@EnableWebSecurity
	static class IncompleteMappingConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.authorizeHttpRequests(AbstractRequestMatcherRegistry::anyRequest)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class IncompleteMappingNoParameterConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
					.authorizeHttpRequests()
					.anyRequest();
			// @formatter:on

			return http.build();
		}

	}

	@Configuration
	@EnableWebSecurity
	@EnableWebMvc
	static class AfterAnyRequestConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().authenticated()
						.requestMatchers("/path").hasRole("USER")
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class CustomAuthorizationManagerConfig {

		static AuthorizationManager<RequestAuthorizationContext> authorizationManager;

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().access(authorizationManager)
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class CustomAuthorizationManagerNoParameterConfig {

		static AuthorizationManager<RequestAuthorizationContext> authorizationManager;

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.authorizeHttpRequests()
					.anyRequest().access(authorizationManager);
			// @formatter:on

			return http.build();
		}

	}

	@Configuration
	@EnableWebSecurity
	static class ObjectPostProcessorConfig {

		ObjectPostProcessor<Object> objectPostProcessor = spy(ReflectingObjectPostProcessor.class);

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().authenticated()
					)
					.build();
			// @formatter:on
		}

		@Bean
		ObjectPostProcessor<Object> objectPostProcessor() {
			return this.objectPostProcessor;
		}

	}

	static class ReflectingObjectPostProcessor implements ObjectPostProcessor<Object> {

		@Override
		public <O> O postProcess(O object) {
			return object;
		}

	}

	@Configuration
	@EnableWebSecurity
	static class RoleUserAnyAuthorityConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.httpBasic()
						.and()
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().hasAnyAuthority("ROLE_USER")
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class RoleUserAuthorityConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.httpBasic()
						.and()
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().hasAuthority("ROLE_USER")
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class RoleUserOrRoleAdminAuthorityConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.httpBasic()
						.and()
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class RoleUserConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().hasRole("USER")
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class RoleHierarchyUserConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().hasRole("USER")
					)
					.build();
			// @formatter:on
		}

		@Bean
		RoleHierarchy roleHierarchy() {
			RoleHierarchyImpl roleHierarchy = new RoleHierarchyImpl();
			roleHierarchy.setHierarchy("ROLE_ADMIN > ROLE_USER");
			return roleHierarchy;
		}

	}

	@Configuration
	@EnableWebSecurity
	static class RoleUserOrAdminConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().hasAnyRole("USER", "ADMIN")
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class DenyAllConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.httpBasic()
						.and()
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().denyAll()
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class PermitAllConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().permitAll()
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class InvokeTwiceDoesNotResetConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.httpBasic()
						.and()
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().authenticated()
					)
					.authorizeHttpRequests(withDefaults())
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebMvc
	@EnableWebSecurity
	static class MvcServletPathConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http, HandlerMappingIntrospector introspector) throws Exception {
			MvcRequestMatcher.Builder mvcMatcherBuilder = new MvcRequestMatcher.Builder(introspector)
				.servletPath("/spring");
			// @formatter:off
			return http
					.authorizeHttpRequests((requests) -> requests
						.requestMatchers(mvcMatcherBuilder.pattern("/")).hasRole("ADMIN")
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class AuthenticatedConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.httpBasic()
						.and()
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().authenticated()
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class ExpressionRoleUserConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().access(new WebExpressionAuthorizationManager("hasRole('USER')"))
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class ExpressionRoleUserOrAdminConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().access(new WebExpressionAuthorizationManager("hasRole('USER') or hasRole('ADMIN')"))
					)
					.build();
			// @formatter:on
		}

	}

	@Configuration
	@EnableWebSecurity
	static class ExpressionIpAddressLocalhostConfig {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
			// @formatter:off
			return http
					.authorizeHttpRequests((requests) -> requests
						.anyRequest().access(new WebExpressionAuthorizationManager("hasIpAddress('127.0.0.1')"))
					)
					.build();
			// @formatter:on
		}

	}

	@EnableWebSecurity
	@Configuration
	@EnableWebMvc
	static class MvcMatcherPathVariablesInLambdaConfig {

		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.httpBasic(withDefaults())
				.authorizeHttpRequests((requests) -> requests
					.requestMatchers("/user/{username}").access(new WebExpressionAuthorizationManager("#username == 'user'"))
				);
			// @formatter:on
			return http.build();
		}

		@RestController
		static class PathController {

			@RequestMapping("/user/{username}")
			String path(@PathVariable("username") String username) {
				return username;
			}

		}

	}

	@Configuration
	@EnableWebSecurity
	static class FullyAuthenticatedConfig {

		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.httpBasic(withDefaults())
				.rememberMe(withDefaults())
				.authorizeHttpRequests((requests) -> requests
					.anyRequest().fullyAuthenticated()
				);
			// @formatter:on
			return http.build();
		}

		@Bean
		UserDetailsService userDetailsService() {
			return new InMemoryUserDetailsManager(TestAuthentication.user());
		}

	}

	@Configuration
	@EnableWebSecurity
	static class RememberMeConfig {

		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.httpBasic(withDefaults())
				.rememberMe(withDefaults())
				.authorizeHttpRequests((requests) -> requests
					.anyRequest().rememberMe()
				);
			// @formatter:on
			return http.build();
		}

		@Bean
		UserDetailsService userDetailsService() {
			return new InMemoryUserDetailsManager(TestAuthentication.user());
		}

	}

	@Configuration
	@EnableWebSecurity
	static class AnonymousConfig {

		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.httpBasic(withDefaults())
				.authorizeHttpRequests((requests) -> requests
					.anyRequest().anonymous()
				);
			// @formatter:on
			return http.build();
		}

	}

	@Configuration
	@EnableWebSecurity
	@EnableWebMvc
	static class AuthorizeHttpRequestsConfig {

		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.httpBasic(withDefaults())
				.authorizeHttpRequests((requests) -> requests
					.requestMatchers("/path/**").permitAll()
					.anyRequest().authenticated()
				);
			// @formatter:on
			return http.build();
		}

	}

	@Configuration
	@EnableWebSecurity
	@EnableWebMvc
	static class DefaultServletConfig {

		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.httpBasic(withDefaults())
				.authorizeHttpRequests((requests) -> requests
					.forServletPattern("/", (root) -> root
						.requestMatchers(HttpMethod.GET, "/path/method/**").permitAll()
						.requestMatchers("/path/path/**").permitAll()
						.anyRequest().authenticated()
					)
				);
			// @formatter:on
			return http.build();
		}

	}

	@Configuration
	@EnableWebSecurity
	@EnableWebMvc
	static class ServletPathConfig {

		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.httpBasic(withDefaults())
				.authorizeHttpRequests((requests) -> requests
					.forServletPattern("/path/*", (root) -> root
						.requestMatchers(HttpMethod.GET, "/method/**").permitAll()
						.requestMatchers("/path/**").permitAll()
						.anyRequest().authenticated()
					)
				);
			// @formatter:on
			return http.build();
		}

	}

	@Configuration
	@EnableWebSecurity
	@EnableWebMvc
	static class ServletMappingConfig {

		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.httpBasic(withDefaults())
				.authorizeHttpRequests((requests) -> requests
					.forServletPattern("*.jsp", (jsp) -> jsp
						.requestMatchers(HttpMethod.GET, "/method/**").permitAll()
						.requestMatchers("/path/**").permitAll()
						.anyRequest().authenticated()
					)
				);
			// @formatter:on
			return http.build();
		}

	}

	@Configuration
	@EnableWebSecurity
	@EnableWebMvc
	static class MixedServletEndpointConfig {

		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.httpBasic(withDefaults())
				.authorizeHttpRequests((requests) -> requests
					.forServletPattern("/", (root) -> root.anyRequest().permitAll())
					.anyRequest().authenticated()
				);
			// @formatter:on
			return http.build();
		}

	}

	@Configuration
	@EnableWebSecurity
	@EnableWebMvc
	static class MixedServletPermitAllConfig {

		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.formLogin((form) -> form.loginPage("/page").permitAll())
				.authorizeHttpRequests((requests) -> requests
					.forServletPattern("/", (root) -> root
						.anyRequest().authenticated()
					)
				);
			// @formatter:on
			return http.build();
		}

	}

	@Configuration
	@EnableWebSecurity
	@EnableWebMvc
	static class DefaultServletAndServletPathConfig {

		@Bean
		SecurityFilterChain chain(HttpSecurity http) throws Exception {
			// @formatter:off
			http
				.httpBasic(withDefaults())
				.authorizeHttpRequests((requests) -> requests
					.forServletPattern("/", (root) -> root
						.requestMatchers("/js/**", "/css/**").permitAll()
					)
					.forServletPattern("/mvc/*", (mvc) -> mvc
						.requestMatchers("/controller/**").authenticated()
					)
					.forServletPattern("*.jsp", (jsp) -> jsp
						.anyRequest().authenticated()
					)
				);
			// @formatter:on
			return http.build();
		}

	}

	@Configuration
	static class AuthorizationEventPublisherConfig {

		private final AuthorizationEventPublisher publisher = mock(AuthorizationEventPublisher.class);

		@Bean
		AuthorizationEventPublisher authorizationEventPublisher() {
			return this.publisher;
		}

	}

	@RestController
	static class BasicController {

		@GetMapping("/")
		void rootGet() {
		}

		@PostMapping("/")
		void rootPost() {
		}

	}

}
