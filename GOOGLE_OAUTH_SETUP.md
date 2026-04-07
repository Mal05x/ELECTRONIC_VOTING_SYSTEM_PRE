# Google OAuth2 Setup Guide

## Overview
Google OAuth allows admin users to sign in with their Google account
instead of (or in addition to) a username/password.

---

## Step 1 — Google Cloud Console

1. Go to https://console.cloud.google.com
2. Create a new project (or select existing): **INEC-EVoting**
3. Navigate to **APIs & Services → Credentials**
4. Click **Create Credentials → OAuth 2.0 Client ID**
5. Application type: **Web application**
6. Name: `MFA E-Voting Admin Dashboard`
7. **Authorized redirect URIs** — add both:
   ```
   https://localhost:8443/login/oauth2/code/google
   https://your-production-domain.com/login/oauth2/code/google
   ```
8. Click **Create** — copy the **Client ID** and **Client Secret**

---

## Step 2 — Backend environment variables

Add to your `.env` file (same directory as `application.yml`):
```env
GOOGLE_CLIENT_ID=your-client-id-here.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your-client-secret-here
```

Then uncomment the OAuth2 block in `application.yml`:
```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope: openid,profile,email
```

---

## Step 3 — pom.xml dependency

Add to `pom.xml` (not yet included — add when enabling OAuth):
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

---

## Step 4 — SecurityConfig.java additions

In `SecurityConfig.filterChain()`, add after `.sessionManagement(...)`:
```java
.oauth2Login(oauth -> oauth
    .loginPage("/login")
    .successHandler(oauthSuccessHandler())
    .userInfoEndpoint(info -> info
        .oidcUserService(oidcUserService()))
)
```

And add a success handler bean that maps the Google email to an existing
AdminUser (or creates one with OBSERVER role):
```java
@Bean
public AuthenticationSuccessHandler oauthSuccessHandler() {
    return (request, response, authentication) -> {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        String email = oidcUser.getEmail();
        AdminUser admin = adminRepo.findByEmailIgnoreCase(email)
            .orElseGet(() -> {
                // Auto-provision with OBSERVER role — promote manually if needed
                AdminUser newAdmin = AdminUser.builder()
                    .username(email)
                    .email(email)
                    .role(AdminUser.AdminRole.OBSERVER)
                    .active(true)
                    .build();
                return adminRepo.save(newAdmin);
            });
        String token = jwtProvider.generateToken(admin.getUsername(), admin.getRole().name());
        // Redirect to frontend with token as query param (handled by AuthContext)
        response.sendRedirect("https://localhost:5173/oauth-callback?token=" + token
            + "&role=" + admin.getRole().name()
            + "&username=" + admin.getUsername());
    };
}
```

---

## Step 5 — Frontend OAuth button

In `LoginPage.jsx`, add a Google Sign-In button:
```jsx
<a
  href="https://localhost:8443/oauth2/authorization/google"
  className="btn btn-surface w-full gap-3 border border-border"
>
  <img src="https://www.gstatic.com/firebasejs/ui/2.0.0/images/auth/google.svg"
       width={18} alt="Google" />
  Sign in with Google
</a>
```

And add an `/oauth-callback` route in `App.jsx` that reads `?token=...`
from the URL, stores it in AuthContext, and redirects to `/dashboard`.

---

## Step 6 — Admin Consent Screen (for production)

1. Go to **APIs & Services → OAuth consent screen**
2. Set User Type: **Internal** (only your org's Google accounts can log in)
3. Fill in App name, support email, authorized domains
4. This is important — **Internal** restricts login to your Google Workspace domain,
   preventing outsiders from even attempting OAuth

---

## Security note
Google OAuth is an **additional** login method, not a replacement.
The existing username/password + ECDSA step-up flow remains.
SUPER_ADMIN operations still require multisig regardless of how the admin authenticated.
