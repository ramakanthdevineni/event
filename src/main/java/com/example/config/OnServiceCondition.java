package com.example.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

public class OnServiceCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(ConditionalOnService.class.getName());
        if (attrs == null) {
            return true;
        }
        String required = String.valueOf(attrs.get("value"));
        String service = context.getEnvironment().getProperty("app.service-name", "all");
        if (service == null || service.isBlank()) {
            service = "all";
        }
        service = service.trim();
        return "all".equalsIgnoreCase(service) || required.equalsIgnoreCase(service);
    }
}
