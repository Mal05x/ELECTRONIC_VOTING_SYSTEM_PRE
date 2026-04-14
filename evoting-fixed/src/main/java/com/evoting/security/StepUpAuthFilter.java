package com.evoting.security;

import com.evoting.model.AdminUser;
import com.evoting.repository.AdminUserRepository;
import com.evoting.service.StepUpAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier; // <-- Added Import
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.util.Map;

/**
 * StepUpAuthFilter — intercepts requests to methods annotated with @RequiresStepUp.
 *
 * Reads X-Action-Signature and X-Action-Nonce headers.
 * Delegates verification to StepUpAuthService.
 * Returns 403 if verification fails.
 */
@Component
@Slf4j
public class StepUpAuthFilter extends OncePerRequestFilter {

    private final StepUpAuthService          stepUpService;
    private final AdminUserRepository        adminRepo;
    private final ObjectMapper               mapper;
    private final RequestMappingHandlerMapping handlerMapping;

    public StepUpAuthFilter(StepUpAuthService stepUpService,
                            AdminUserRepository adminRepo,
                            ObjectMapper mapper,
                            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) { // <-- Added @Qualifier
        this.stepUpService  = stepUpService;
        this.adminRepo      = adminRepo;
        this.mapper         = mapper;
        this.handlerMapping = handlerMapping;
    }

    /**
     * Skip step-up auth processing for multipart/form-data requests (file uploads).
     *
     * Why: getAnnotation() calls handlerMapping.getHandler(request), which does
     * internal Spring request processing. For multipart requests, this corrupts the
     * boundary metadata before DispatcherServlet wraps the request in a
     * MultipartHttpServletRequest — causing "Current request is not a multipart request"
     * when the controller tries to bind @RequestParam MultipartFile.
     *
     * File upload endpoints (@RequestParam MultipartFile) never use @RequiresStepUp,
     * so skipping them here loses nothing and fixes the upload.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws IOException, jakarta.servlet.ServletException {
        // Check if the target handler has @RequiresStepUp
        RequiresStepUp annotation = getAnnotation(request);
        if (annotation == null) {
            chain.doFilter(request, response);
            return;
        }

        String actionType = annotation.value();
        String nonce      = request.getHeader("X-Action-Nonce");
        String signature  = request.getHeader("X-Action-Signature");

        if (nonce == null || nonce.isBlank() || signature == null || signature.isBlank()) {
            sendError(response, 403, "MISSING_STEP_UP",
                    "This action requires step-up authentication. " +
                            "Include X-Action-Nonce and X-Action-Signature headers.");
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            sendError(response, 401, "UNAUTHENTICATED", "Not authenticated");
            return;
        }

        try {
            AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName()).orElseThrow();
            stepUpService.verify(admin.getId(), nonce, signature, actionType);
            // Verification passed — proceed
            chain.doFilter(request, response);
        } catch (SecurityException e) {
            log.warn("[STEP-UP] Verification failed for {}: {}", auth.getName(), e.getMessage());
            sendError(response, 403, "STEP_UP_FAILED", e.getMessage());
        } catch (Exception e) {
            log.error("[STEP-UP] Unexpected error: {}", e.getMessage());
            sendError(response, 500, "STEP_UP_ERROR", "Authorization check failed");
        }
    }

    private RequiresStepUp getAnnotation(HttpServletRequest request) {
        try {
            HandlerExecutionChain chain = handlerMapping.getHandler(request);
            if (chain == null) return null;
            Object handler = chain.getHandler();
            if (!(handler instanceof HandlerMethod)) return null;
            return ((HandlerMethod) handler).getMethodAnnotation(RequiresStepUp.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void sendError(HttpServletResponse response, int status,
                           String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        mapper.writeValue(response.getWriter(), Map.of(
                "error", message,
                "code",  code
        ));
    }
}