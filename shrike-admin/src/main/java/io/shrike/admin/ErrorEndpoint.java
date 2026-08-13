package io.shrike.admin;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Where the servlet container sends a failure no handler of this facade answered, answered in the one
 * shape every other failure uses.
 *
 * <p>{@link ErrorResponses} covers what reaches a handler. This covers what does not: a path the
 * container itself refuses before the dispatcher is asked — {@code /WEB-INF/x} and {@code /META-INF/x}
 * are the two it always has — and anything a filter fails at outside the dispatcher. Both arrive here
 * as a forward carrying {@link RequestDispatcher#ERROR_STATUS_CODE}, and without a controller of its
 * own that forward is answered by Spring Boot's default error body, which is a different shape carrying
 * a timestamp and the caller's path. Implementing {@link ErrorController} is what stands that
 * controller down, and answering with {@link ErrorBody} is what makes the README's "every failure is
 * one shape" a sentence about this build rather than about most of it.
 *
 * <p><strong>A direct {@code GET /error} is answered 404.</strong> There is no forward behind it and
 * therefore no original status to resolve, and the honest reading is the plain one: this facade serves
 * three paths, {@code /error} is not one of them, and a caller that asked for it asked for a path that
 * is not here. Answering 500 instead — which is what the default controller does, under a status of
 * 999 — would report a failure that did not happen.
 *
 * <p>Requests the container refuses <em>before</em> the servlet stack sees them at all, such as a
 * request target holding a broken percent-escape, never reach this class. Those are answered with a
 * status code and an empty body; {@link ShrikeAdminApplication#containerErrorReports()} is where that
 * is arranged.
 */
@Controller
@RequestMapping("${spring.web.error.path:/error}")
public class ErrorEndpoint implements ErrorController {

    /**
     * @param request the forwarded request, whose dispatcher attributes carry the status that was being
     *                answered when this facade was cut out of the loop
     * @return that status, with this facade's sentence for it
     */
    @RequestMapping
    @ResponseBody
    ResponseEntity<ErrorBody> answer(HttpServletRequest request) {
        HttpStatus status = statusOf(request);
        return ErrorResponses.answer(status, ErrorResponses.sentenceFor(status));
    }

    /**
     * @return the status the container forwarded here with, {@link HttpStatus#NOT_FOUND} when nothing
     *         forwarded and the caller simply asked for this path, and
     *         {@link HttpStatus#INTERNAL_SERVER_ERROR} when the code is one no status names
     */
    private static HttpStatus statusOf(HttpServletRequest request) {
        if (!(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code)) {
            return HttpStatus.NOT_FOUND;
        }
        HttpStatus status = HttpStatus.resolve(code);
        return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
