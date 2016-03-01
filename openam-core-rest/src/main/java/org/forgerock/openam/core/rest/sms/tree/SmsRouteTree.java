/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015-2016 ForgeRock AS.
 */

package org.forgerock.openam.core.rest.sms.tree;

import static org.forgerock.authz.filter.crest.AuthorizationFilters.*;
import static org.forgerock.json.resource.Requests.*;
import static org.forgerock.json.resource.ResourceException.*;
import static org.forgerock.json.resource.ResourcePath.*;
import static org.forgerock.json.resource.Responses.*;
import static org.forgerock.json.resource.Router.*;
import static org.forgerock.openam.rest.query.QueryResponsePresentation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.forgerock.authz.filter.crest.api.CrestAuthorizationModule;
import org.forgerock.guava.common.base.Predicate;
import org.forgerock.http.routing.RoutingMode;
import org.forgerock.http.routing.UriRouterContext;
import org.forgerock.json.JsonPointer;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.Filter;
import org.forgerock.json.resource.FilterChain;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.Request;
import org.forgerock.json.resource.RequestHandler;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourcePath;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.Router;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openam.forgerockrest.utils.MatchingResourcePath;
import org.forgerock.openam.utils.StringUtils;
import org.forgerock.services.context.Context;
import org.forgerock.services.routing.RouteMatcher;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.query.QueryFilter;

/**
 * Represents a {@code Router} tree. Allows the structuring of Routers in a tree like manner whilst
 * offering a flat entry point to add and remove routes and services.
 *
 * @since 13.0.0
 */
public class SmsRouteTree implements RequestHandler {

    private static final QueryFilter<JsonPointer> ALWAYS_TRUE = QueryFilter.alwaysTrue();
    private static final String NEXTDESCENDENTS_QUERY = "nextdescendents";
    private final Predicate<String> handlesFunction;

    final Map<MatchingResourcePath, CrestAuthorizationModule> authzModules;
    final CrestAuthorizationModule defaultAuthzModule;
    final Router router;
    final ResourcePath path;
    private final boolean isRoot;
    private final Set<SmsRouteTree> subTrees;
    private final Filter filter;
    private final Map<String, RequestHandler> handlers = new LinkedHashMap<>();
    private final String uriTemplate;

    /**
     * Creates a {@code SmsRouteTree} instance.
     * @param authzModules Authz modules to use for specific matching resource paths.
     * @param defaultAuthzModule Auth module to use if no matching resouce path exists in {@code authzModules}.
     * @param isRoot {@code true} if this {@code SmsRouteTree} is the root of the tree.
     * @param router The {@code Router} instance.
     * @param filter The filter to wrap around all routes.
     * @param path The path of this tree element.
     * @param uriTemplate
     */
    SmsRouteTree(Map<MatchingResourcePath, CrestAuthorizationModule> authzModules,
            CrestAuthorizationModule defaultAuthzModule, boolean isRoot, Router router, Filter filter,
            ResourcePath path, Predicate<String> handlesFunction, String uriTemplate) {
        this.authzModules = authzModules;
        this.defaultAuthzModule = defaultAuthzModule;
        this.isRoot = isRoot;
        this.router = router;
        this.path = path;
        this.subTrees = new HashSet<>();
        this.filter = filter;
        this.handlesFunction = handlesFunction;
        this.uriTemplate = uriTemplate;
    }

    final void addSubTree(SmsRouteTree subTree) {
        this.subTrees.add(subTree);
        router.addRoute(RoutingMode.STARTS_WITH, uriTemplate(subTree.uriTemplate), subTree);
    }

    /**
     * Returns the {@code SmsRouteTree} instance that the route should be added to.
     *
     * @param serviceName The name of the service that the route is being added for.
     * @return The {@code SmsRouteTree} to handle the route.
     */
    public SmsRouteTree handles(String serviceName) {
        if (handlesFunction.apply(serviceName)) {
            return this;
        }
        for (SmsRouteTree subTree : subTrees) {
            SmsRouteTree tree = subTree.handles(serviceName);
            if (tree != null) {
                return tree;
            }
        }
        if (isRoot) {
            return this;
        } else {
            return null;
        }
    }

    /**
     * Adds a new route to this router for the provided request handler. New routes may be added
     * while this router is processing requests.
     *
     * @param mode Indicates how the URI template should be matched against resource names.
     * @param uriTemplate The URI template which request resource names must match.
     * @param handler The request handler to which matching requests will be routed.
     * @return An opaque handle for the route which may be used for removing the route later, and the tree the route
     * leads to.
     */
    public final Route addRoute(RoutingMode mode, String uriTemplate, RequestHandler handler) {
        return addRoute(false, mode, uriTemplate, handler);
    }

    final Route addRoute(boolean internal, RoutingMode mode, String uriTemplate, RequestHandler handler) {
        CrestAuthorizationModule authzModule = authzModules.get(MatchingResourcePath.match(concat(path, uriTemplate)));
        if (authzModule != null || !internal) {
            handler = createAuthorizationFilter(handler, authzModule == null ? defaultAuthzModule : authzModule);
        }

        if (filter != null) {
            handler = new FilterChain(handler, filter);
        }

        if (!uriTemplate.isEmpty()) {
            SmsRouteTree subtree = new SmsRouteTreeBuilder(uriTemplate).build(this);
            handlers.put(uriTemplate, subtree);
            if (mode.equals(RoutingMode.STARTS_WITH)) {
                subtree.router.setDefaultRoute(handler);
            } else {
                subtree.router.addRoute(RoutingMode.EQUALS, uriTemplate(""), handler);
            }
            return new Route(router.addRoute(RoutingMode.STARTS_WITH, uriTemplate(uriTemplate), subtree), subtree);
        } else {
            return new Route(router.addRoute(mode, uriTemplate(uriTemplate), handler), this);
        }
    }

    /**
     * Removes a route from this router. Routes may be removed while this router is
     * processing requests.
     *
     * @param route The route to be removed.
     * @return {@code true} if at the route was found and removed.
     */
    public final boolean removeRoute(RouteMatcher<Request> route) {
        return router.removeRoute(route);
    }

    @Override
    public Promise<QueryResponse, ResourceException> handleQuery(Context context, QueryRequest request,
            QueryResourceHandler handler) {
        String remainingUri = context.asContext(UriRouterContext.class).getRemainingUri();
        if (NEXTDESCENDENTS_QUERY.equals(request.getQueryId()) && remainingUri.isEmpty()) {
            final List<ResourceResponse> responses = new ArrayList<>();
            for (Map.Entry<String, RequestHandler> subRoute : handlers.entrySet()) {
                if (!subRoute.getKey().equals("")) {
                    final ResourcePath subPath = resourcePath(subRoute.getKey());
                    try {
                        readInstances(context, responses, subPath, subRoute.getValue());
                    } catch (ResourceException e) {
                        return e.asPromise();
                    }
                }
            }
            return perform(handler, request, responses);
        } else {
            return router.handleQuery(context, request, handler);
        }
    }

    private void readInstances(Context context, List<ResourceResponse> responses,
            ResourcePath subPath, RequestHandler handler) throws ResourceException {
        try {
            QueryRequest subRequest = newQueryRequest(empty()).setQueryFilter(ALWAYS_TRUE);
            handler.handleQuery(context, subRequest, new ChildQueryResourceHandler(subPath, responses))
                    .getOrThrowUninterruptibly();
        } catch (ResourceException e) {
            if (e.getCode() == NOT_SUPPORTED || e.getCode() == BAD_REQUEST) {
                getSingletonInstance(context, responses, subPath, handler);
            } else if (e.getCode() != NOT_FOUND){
                throw e;
            }
        }
    }

    private void getSingletonInstance(Context context, List<ResourceResponse> responses, ResourcePath subPath,
            RequestHandler handler) throws ResourceException {
        try {
            ResourceResponse instance = handler.handleRead(context, newReadRequest(empty()))
                    .getOrThrowUninterruptibly();
            String id = subPath.toString();
            responses.add(newResourceResponse(id, instance.getRevision(),
                    instance.getContent().put("_id", id)));
        } catch (ResourceException e) {
            if (e.getCode() == NOT_SUPPORTED || e.getCode() == BAD_REQUEST) {
                findFurtherDescendents(context, responses, subPath, handler);
            } else if (e.getCode() != NOT_FOUND) {
                throw e;
            }
        }
    }

    private void findFurtherDescendents(Context context, List<ResourceResponse> responses, ResourcePath subPath,
            RequestHandler handler) throws ResourceException {
        QueryRequest subRequest = newQueryRequest(empty()).setQueryId(NEXTDESCENDENTS_QUERY);
        try {
            handler.handleQuery(context, subRequest, new ChildQueryResourceHandler(subPath, responses))
                    .getOrThrowUninterruptibly();
        } catch (ResourceException e) {
            if (e.getCode() != NOT_FOUND) {
                throw e;
            }
        }
    }

    @Override
    public Promise<ActionResponse, ResourceException> handleAction(Context context, ActionRequest request) {
        return router.handleAction(context, request);
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleCreate(Context context, CreateRequest request) {
        return router.handleCreate(context, request);
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleDelete(Context context, DeleteRequest request) {
        return router.handleDelete(context, request);
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handlePatch(Context context, PatchRequest request) {
        return router.handlePatch(context, request);
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleRead(Context context, ReadRequest request) {
        return router.handleRead(context, request);
    }

    @Override
    public Promise<ResourceResponse, ResourceException> handleUpdate(Context context, UpdateRequest request) {
        return router.handleUpdate(context, request);
    }

    static ResourcePath concat(ResourcePath parent, String child) {
        if (StringUtils.isEmpty(child)) {
            return parent;
        }
        ResourcePath childPath = resourcePath(child);
        return parent.concat(childPath);
    }

    private static class ChildQueryResourceHandler implements QueryResourceHandler {
        private final ResourcePath subPath;
        private final List<ResourceResponse> responses;

        private ChildQueryResourceHandler(ResourcePath subPath, List<ResourceResponse> responses) {
            this.subPath = subPath;
            this.responses = responses;
        }

        public boolean handleResource(ResourceResponse resource) {
                JsonValue content = resource.getContent();
                String id = subPath.child(content.get("_id").asString()).toString();
                responses.add(newResourceResponse(id, resource.getRevision(), content.put("_id", id)));
                return true;
            }
    }

    /**
     * A matching pair for an {@code SmsRouteTree} route that consists of the tree routed to, and the route matcher
     * that leads there.
     */
    public static class Route {
        /** The matcher that leads to the tree. */
        public final RouteMatcher<Request> matcher;
        /** The tree that contains this route. */
        public final SmsRouteTree tree;

        private Route(RouteMatcher<Request> matcher, SmsRouteTree tree) {
            this.matcher = matcher;
            this.tree = tree;
        }
    }
}
