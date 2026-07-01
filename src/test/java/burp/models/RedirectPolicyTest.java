package burp.models;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectPolicyTest {

    @Test
    void defaultsUseTenHopsAndStripSensitiveMode() {
        RedirectPolicy policy = RedirectPolicy.defaults();

        assertThat(policy.maxHops).isEqualTo(10);
        assertThat(policy.crossOriginMode).isEqualTo(RedirectCrossOriginMode.STRIP_SENSITIVE);
        assertThat(policy.effectiveSensitiveHeaderNames())
                .containsExactly(
                        "Authorization",
                        "Cookie",
                        "Proxy-Authorization",
                        "X-API-Key",
                        "API-Key",
                        "X-Auth-Token");
    }

    @Test
    void normalizeClampsHopLimitAndDeduplicatesAdditionalSensitiveNames() {
        RedirectPolicy policy = new RedirectPolicy();
        policy.maxHops = 0;
        policy.crossOriginMode = null;
        policy.additionalSensitiveHeaderNames = new ArrayList<>(Arrays.asList("X-Tenant-ID", " authorization ", "", "x-tenant-id", null));

        policy.normalize();

        assertThat(policy.maxHops).isEqualTo(1);
        assertThat(policy.crossOriginMode).isEqualTo(RedirectCrossOriginMode.STRIP_SENSITIVE);
        assertThat(policy.additionalSensitiveHeaderNames).containsExactly("X-Tenant-ID", "authorization");
        assertThat(policy.effectiveSensitiveHeaderNames()).contains("X-Tenant-ID");
    }

    @Test
    void normalizeCapsHopLimitAtTwenty() {
        RedirectPolicy policy = new RedirectPolicy();
        policy.maxHops = 99;

        policy.normalize();

        assertThat(policy.maxHops).isEqualTo(20);
    }

    @Test
    void copyOfProducesDefensiveCopies() {
        RedirectPolicy policy = new RedirectPolicy();
        policy.additionalSensitiveHeaderNames.add("X-Tenant-ID");
        TrustedRedirectRule rule = new TrustedRedirectRule();
        rule.sourceOrigin = "https://api.example.test:443";
        rule.targetOrigin = "https://auth.example.test:443";
        rule.allowedHeaderNames = List.of("Authorization");
        policy.trustedRules.add(rule);

        RedirectPolicy copy = RedirectPolicy.copyOf(policy);
        copy.additionalSensitiveHeaderNames.add("X-Trace-ID");
        copy.trustedRules.get(0).allowedHeaderNames.add("X-Trace-ID");

        assertThat(policy.additionalSensitiveHeaderNames).containsExactly("X-Tenant-ID");
        assertThat(policy.trustedRules.get(0).allowedHeaderNames).containsExactly("Authorization");
    }

    @Test
    void trustedHeaderAllowanceRequiresExactOriginsAndHttpsTargets() {
        RedirectPolicy policy = new RedirectPolicy();
        TrustedRedirectRule rule = new TrustedRedirectRule();
        rule.sourceOrigin = "https://api.example.test:443";
        rule.targetOrigin = "https://auth.example.test:443";
        rule.allowedHeaderNames = List.of("Authorization", "X-Tenant-ID", "Proxy-Authorization");
        policy.trustedRules.add(rule);
        policy.normalize();

        assertThat(policy.isTrustedHeaderAllowed(
                "https://api.example.test",
                "https://auth.example.test",
                "Authorization")).isTrue();
        assertThat(policy.isTrustedHeaderAllowed(
                "https://api.example.test",
                "https://auth.example.test",
                "X-Tenant-ID")).isTrue();
        assertThat(policy.isTrustedHeaderAllowed(
                "https://api.example.test",
                "https://auth.example.test",
                "Proxy-Authorization")).isFalse();
        assertThat(policy.isTrustedHeaderAllowed(
                "https://api.example.test:444",
                "https://auth.example.test",
                "Authorization")).isFalse();
        assertThat(policy.isTrustedHeaderAllowed(
                "https://api.example.test",
                "https://other.example.test",
                "Authorization")).isFalse();
        assertThat(policy.isTrustedHeaderAllowed(
                "https://api.example.test",
                "http://auth.example.test",
                "Authorization")).isFalse();
    }

    @Test
    void normalizeRejectsInvalidTrustedOriginsAndWildcardRules() {
        RedirectPolicy policy = new RedirectPolicy();

        TrustedRedirectRule valid = new TrustedRedirectRule();
        valid.sourceOrigin = "https://api.example.test:443";
        valid.targetOrigin = "https://auth.example.test:443";
        valid.allowedHeaderNames = List.of("Authorization");

        TrustedRedirectRule invalidPath = new TrustedRedirectRule();
        invalidPath.sourceOrigin = "https://api.example.test/path";
        invalidPath.targetOrigin = "https://auth.example.test:443";
        invalidPath.allowedHeaderNames = List.of("Authorization");

        TrustedRedirectRule invalidWildcard = new TrustedRedirectRule();
        invalidWildcard.sourceOrigin = "https://*.example.test";
        invalidWildcard.targetOrigin = "https://auth.example.test:443";
        invalidWildcard.allowedHeaderNames = List.of("Authorization");

        policy.trustedRules = List.of(valid, invalidPath, invalidWildcard);
        policy.normalize();

        assertThat(policy.trustedRules).hasSize(1);
        assertThat(policy.trustedRules.get(0).sourceOrigin).isEqualTo("https://api.example.test:443");
        assertThat(policy.trustedRules.get(0).targetOrigin).isEqualTo("https://auth.example.test:443");
    }

    @Test
    void proxyAuthorizationCanNeverBeTrusted() {
        RedirectPolicy policy = new RedirectPolicy();
        TrustedRedirectRule rule = new TrustedRedirectRule();
        rule.sourceOrigin = "https://api.example.test:443";
        rule.targetOrigin = "https://auth.example.test:443";
        rule.allowedHeaderNames = List.of("Proxy-Authorization");
        policy.trustedRules.add(rule);
        policy.normalize();

        assertThat(policy.trustedRules.get(0).allowedHeaderNames).isEmpty();
        assertThat(policy.isTrustedHeaderAllowed(
                "https://api.example.test",
                "https://auth.example.test",
                "Proxy-Authorization")).isFalse();
    }
}
