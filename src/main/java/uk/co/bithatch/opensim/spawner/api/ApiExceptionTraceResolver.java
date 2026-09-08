package uk.co.bithatch.opensim.spawner.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.HandlerExceptionResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionTraceResolver implements HandlerExceptionResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionTraceResolver.class);

    @Override
    @Nullable
    public ModelAndView resolveException(HttpServletRequest request,
            HttpServletResponse response,
            @Nullable Object handler,
            Exception ex) {
        if (!isApiRequest(request)) {
            return null;
        }

        var status = resolveStatus(ex);
        var handlerName = resolveHandlerName(handler);
        LOG.error("API exception: {} {} -> {} at {}",
                request.getMethod(),
                requestUriWithQuery(request),
                status,
                handlerName,
                ex);

        // Return null so Spring continues with its default exception handling.
        return null;
    }

    private static boolean isApiRequest(HttpServletRequest request) {
        var uri = request.getRequestURI();
        return uri.startsWith("/api/") || uri.startsWith("/ui/api/");
    }

    private static String requestUriWithQuery(HttpServletRequest request) {
        var query = request.getQueryString();
        return query == null || query.isBlank() ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    private static int resolveStatus(Exception ex) {
        if (ex instanceof ResponseStatusException statusException) {
            return statusException.getStatusCode().value();
        }
        var responseStatus = ex.getClass().getAnnotation(ResponseStatus.class);
        if (responseStatus != null) {
            return responseStatus.code().value();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private static String resolveHandlerName(@Nullable Object handler) {
        if (handler instanceof HandlerMethod method) {
            var beanType = method.getBeanType().getSimpleName();
            return beanType + "#" + method.getMethod().getName();
        }
        return handler == null ? "<no-handler>" : handler.getClass().getSimpleName();
    }
}
