package org.rakam.server.http;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wordnik.swagger.models.Swagger;
import com.wordnik.swagger.util.Json;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.server.http.annotations.ParamBody;

import javax.ws.rs.Path;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.rakam.server.http.util.Lambda.produceLambda;

/**
 * Created by buremba on 20/12/13.
 */
public class HttpServer {
    private static String REQUEST_HANDLER_ERROR_MESSAGE = "Request handler method %s.%s couldn't converted to request handler lambda expression: \n %s";
    private static final ObjectMapper DEFAULT_MAPPER;

    static {
        DEFAULT_MAPPER = new ObjectMapper();
//        DEFAULT_MAPPER.findAndRegisterModules();
    }

    public final RouteMatcher routeMatcher;
    private final static InternalLogger LOGGER =InternalLoggerFactory.getInstance(HttpServer.class);
    private final Swagger swagger;

    private final ObjectMapper mapper;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private Channel channel;

    public HttpServer(Set<HttpService> httpServicePlugins, Set<WebSocketService> websocketServices, Swagger swagger, EventLoopGroup eventLoopGroup, ObjectMapper mapper) {
        this.routeMatcher = new RouteMatcher();

        this.workerGroup = requireNonNull(eventLoopGroup, "eventLoopGroup is null");
        this.swagger = requireNonNull(swagger, "swagger is null");
        this.mapper = mapper;

        this.bossGroup = new NioEventLoopGroup(1);
        registerEndPoints(requireNonNull(httpServicePlugins, "httpServices is null"));
        registerWebSocketPaths(requireNonNull(websocketServices, "webSocketServices is null"));
        routeMatcher.add(HttpMethod.GET, "/api/swagger.json", request -> {
            String content;

            try {
                content = Json.mapper().writeValueAsString(swagger);
            } catch (JsonProcessingException e) {
                request.response("Error").end();
                return;
            }

            request.response(content).end();

        });
    }

    public HttpServer(Set<HttpService> httpServicePlugins, Set<WebSocketService> websocketServices, Swagger swagger) {
        this(httpServicePlugins, websocketServices, swagger, new NioEventLoopGroup(), DEFAULT_MAPPER);
    }

    private void registerWebSocketPaths(Set<WebSocketService> webSocketServices) {
        webSocketServices.forEach(service -> {
            String path = service.getClass().getAnnotation(Path.class).value();
            if (path == null) {
                throw new IllegalStateException(format("Classes that implement WebSocketService must have %s annotation.",
                        Path.class.getCanonicalName()));
            }
            routeMatcher.add(path, service);
        });
    }


    private void registerEndPoints(Set<HttpService> httpServicePlugins) {
        SwaggerReader reader = new SwaggerReader(swagger);
        httpServicePlugins.forEach(service -> {

            reader.read(service.getClass());
            String mainPath = service.getClass().getAnnotation(Path.class).value();
            if (mainPath == null) {
                throw new IllegalStateException(format("Classes that implement HttpService must have %s annotation.", Path.class.getCanonicalName()));
            }
            RouteMatcher.MicroRouteMatcher microRouteMatcher = new RouteMatcher.MicroRouteMatcher(routeMatcher, mainPath);
            for (Method method : service.getClass().getMethods()) {
                Path annotation = method.getAnnotation(Path.class);

                if (annotation != null) {
                    String lastPath = annotation.value();
                    JsonRequest jsonRequest = method.getAnnotation(JsonRequest.class);
                    boolean mapped = false;
                    for (Annotation ann : method.getAnnotations()) {
                        javax.ws.rs.HttpMethod methodAnnotation = ann.annotationType().getAnnotation(javax.ws.rs.HttpMethod.class);

                        if (methodAnnotation != null) {
                            HttpRequestHandler handler = null;
                            HttpMethod httpMethod = HttpMethod.valueOf(methodAnnotation.value());
                            try {
                                if (jsonRequest == null) {
                                    handler = generateRequestHandler(service, method);
                                } else if (httpMethod == HttpMethod.POST) {
                                    mapped = true;

                                    if(method.getParameterCount() == 1 && method.getParameters()[0].getAnnotation(ParamBody.class)!=null) {
                                        handler = createPostRequestHandler(service, method);
                                    }else {
                                        handler = createParametrizedPostRequestHandler(service, method);
                                    }
                                } else if (httpMethod == HttpMethod.GET) {
                                    mapped = true;
                                    handler = createGetRequestHandler(service, method);
                                }
                            } catch (Throwable e) {
                                throw new RuntimeException(format(REQUEST_HANDLER_ERROR_MESSAGE,
                                        method.getClass().getName(), method.getName(), e));
                            }

                            microRouteMatcher.add(lastPath, httpMethod, handler);
                            if (lastPath.equals("/"))
                                microRouteMatcher.add("", httpMethod, handler);
                        }
                    }
                    if (!mapped && jsonRequest != null) {
//                        throw new IllegalStateException(format("Methods that have @JsonRequest annotation must also include one of HTTPStatus annotations. %s", method.toString()));
                        try {
                            if(method.getParameterCount() == 1 && method.getParameters()[0].getAnnotation(ParamBody.class)!=null) {
                                microRouteMatcher.add(lastPath, HttpMethod.POST, createPostRequestHandler(service, method));
                            }else {
                                microRouteMatcher.add(lastPath, HttpMethod.POST, createParametrizedPostRequestHandler(service, method));
                            }
                        } catch (Throwable e) {
                            throw new RuntimeException(format(REQUEST_HANDLER_ERROR_MESSAGE,
                                    method.getDeclaringClass().getName(), method.getName(), e));
                        }
                    }
                }
            }
        });
    }

    private static class RequestParameter {
        public final String name;
        public final Class type;
        public final boolean required;

        private RequestParameter(String name, Class type, boolean required) {
            this.name = name;
            this.type = type;
            this.required = required;
        }
    }

    private HttpRequestHandler createParametrizedPostRequestHandler(HttpService service, Method method) {
        ArrayList<RequestParameter> objects = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            ApiParam apiParam = parameter.getAnnotation(ApiParam.class);
            if(apiParam != null) {
                objects.add(new RequestParameter(apiParam.name(), parameter.getType(), apiParam == null ? false : apiParam.required()));
            }else {
                objects.add(new RequestParameter(parameter.getName(), parameter.getType(), false));
            }
        }

        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());

        return new HttpRequestHandler() {
            @Override
            public void handle(RakamHttpRequest request) {
                request.bodyHandler(new Consumer<String>() {
                    @Override
                    public void accept(String body) {
                        ObjectNode node;
                        try {
                            node = (ObjectNode) mapper.readTree(body);
                        } catch (ClassCastException e) {
                            request.jsonResponse(errorMessage("Body must be an json object.", 400), BAD_REQUEST).end();
                            return;
                        } catch (UnrecognizedPropertyException e) {
                        returnError(request, "Unrecognized field: " + e.getPropertyName(), 400);
                        return;
                    } catch (InvalidFormatException e) {
                        returnError(request, format("Field value couldn't validated: %s ", e.getOriginalMessage()), 400);
                        return;
                    } catch (JsonMappingException e) {
                        returnError(request, e.getCause()!=null ? e.getCause().getMessage() : e.getMessage(), 400);
                        return;
                    } catch (JsonParseException e) {
                        returnError(request, format("Couldn't parse json: %s ", e.getOriginalMessage()), 400);
                        return;
                    } catch (IOException e) {
                        returnError(request, format("Error while mapping json: ", e.getMessage()), 400);
                        return;
                    }

                        List<Object> values = new ArrayList<>(objects.size());
                        for (RequestParameter param : objects) {
                            JsonNode value = node.get(param.name);
                            if(param.required && (value == null || value == NullNode.getInstance())) {
                                request.jsonResponse(errorMessage(param.name+" parameter is required", 400), BAD_REQUEST).end();
                                return;
                            }
                            values.add(mapper.convertValue(value, param.type));
                        }

                        Object invoke;
                        try {
                            invoke = method.invoke(service, values.toArray());
                        } catch (IllegalAccessException e) {
                            request.response("not ok").end();
                            return;
                        } catch (InvocationTargetException e) {
                            e.getTargetException().printStackTrace();
                            request.jsonResponse(errorMessage(e.getMessage(), 500), HttpResponseStatus.INTERNAL_SERVER_ERROR).end();
                            return;
                        } catch (HttpRequestException e) {
                            int statusCode = e.getStatusCode();
                            String encode = encodeJson(errorMessage(e.getMessage(), statusCode));
                            request.response(encode, HttpResponseStatus.valueOf(statusCode)).end();
                            return;
                        } catch (Exception e) {
                            LOGGER.error("An uncaught exception raised while processing request.", e);
                            ObjectNode errorMessage = errorMessage("error processing request.", HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
                            request.response(encodeJson(errorMessage), BAD_REQUEST).end();
                            return;
                        }

                        if (isAsync) {
                            handleAsyncJsonRequest(service, request, (CompletionStage) invoke);
                        } else {
                            request.jsonResponse(invoke).end();
                        }
                    }
                });
            }
        };
    }

    private static BiFunction<HttpService, Object, Object> generateJsonRequestHandler(Method method) throws Throwable {
//        if (!Object.class.isAssignableFrom(method.getReturnType()) ||
//                method.getParameterCount() != 1 ||
//                !method.getParameterTypes()[0].equals(JsonNode.class))
//            throw new IllegalStateException(format("The signature of @JsonRequest methods must be [Object (%s)]", JsonNode.class.getCanonicalName()));

        MethodHandles.Lookup caller = MethodHandles.lookup();
        return produceLambda(caller, method, BiFunction.class.getMethod("apply", Object.class, Object.class));
    }

    private static HttpRequestHandler generateRequestHandler(HttpService service, Method method) throws Throwable {
        if (!method.getReturnType().equals(void.class) ||
                method.getParameterCount() != 1 ||
                !method.getParameterTypes()[0].equals(RakamHttpRequest.class))
            throw new IllegalStateException(format("The signature of HTTP request methods must be [void ()]", RakamHttpRequest.class.getCanonicalName()));

        MethodHandles.Lookup caller = MethodHandles.lookup();

        if (Modifier.isStatic(method.getModifiers())) {
            Consumer<RakamHttpRequest> lambda;
            lambda = produceLambda(caller, method, Consumer.class.getMethod("accept", Object.class));
            return request -> lambda.accept(request);
        } else {
            BiConsumer<HttpService, RakamHttpRequest> lambda;
            lambda = produceLambda(caller, method, BiConsumer.class.getMethod("accept", Object.class, Object.class));
            return request -> lambda.accept(service, request);
        }
    }

    private HttpRequestHandler createPostRequestHandler(HttpService service, Method method) throws Throwable {

        BiFunction<HttpService, Object, Object> function = generateJsonRequestHandler(method);
        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());
        Class<?> jsonClazz = method.getParameterTypes()[0];
//        boolean returnString = false;
//        if(isAsync) {
//            Type returnType = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
//            returnString = returnType.equals(String.class);
//        }

        return (request) -> request.bodyHandler(obj -> {
            Object json;
            try {
                json = mapper.readValue(obj, jsonClazz);
            } catch (UnrecognizedPropertyException e) {
                returnError(request, "Unrecognized field: " + e.getPropertyName(), 400);
                return;
            } catch (InvalidFormatException e) {
                returnError(request, format("Field value couldn't validated: %s ", e.getOriginalMessage()), 400);
                return;
            } catch (JsonMappingException e) {
                returnError(request, e.getCause()!=null ? e.getCause().getMessage() : e.getMessage(), 400);
                return;
            } catch (JsonParseException e) {
                returnError(request, format("Couldn't parse json: %s ", e.getOriginalMessage()), 400);
                return;
            } catch (IOException e) {
                returnError(request, format("Error while mapping json: ", e.getMessage()), 400);
                return;
            }
            if (isAsync) {
                CompletionStage apply = (CompletionStage) function.apply(service, json);
                handleAsyncJsonRequest(service, request, apply);
            } else {
                handleJsonRequest(service, request, function, json);
            }
        });
    }

    private HttpRequestHandler createGetRequestHandler(HttpService service, Method method) throws Throwable {
        BiFunction<HttpService, Object, Object> function = generateJsonRequestHandler(method);

        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());

        if (method.getParameterTypes()[0].equals(ObjectNode.class)) {
            return (request) -> {
                ObjectNode json = generate(request.params());

                if (isAsync) {
                    CompletionStage apply = (CompletionStage) function.apply(service, json);
                    handleAsyncJsonRequest(service, request, apply);
                } else {
                    handleJsonRequest(service, request, function, json);
                }
            };

        } else {
            return (request) -> {

                ObjectNode json = generate(request.params());
                if (isAsync) {
                    CompletionStage apply = (CompletionStage) function.apply(service, json);
                    handleAsyncJsonRequest(service, request, apply);
                } else {
                    handleJsonRequest(service, request, function, json);
                }
            };
        }
    }

    private void handleJsonRequest(HttpService serviceInstance, RakamHttpRequest request, BiFunction<HttpService, Object, Object> function, Object json) {
        try {
            Object apply = function.apply(serviceInstance, json);
            String response = encodeJson(apply);
            request.response(response).end();
        } catch (HttpRequestException e) {
            int statusCode = e.getStatusCode();
            String encode = encodeJson(errorMessage(e.getMessage(), statusCode));
            request.response(encode, HttpResponseStatus.valueOf(statusCode)).end();
        } catch (Exception e) {
            LOGGER.error("An uncaught exception raised while processing request.", e);
            ObjectNode errorMessage = errorMessage("error processing request.", HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
            request.response(encodeJson(errorMessage), BAD_REQUEST).end();
        }
    }

    private String encodeJson(Object apply) {
        try {
            return mapper.writeValueAsString(apply);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("couldn't serialize object");
        }
    }

    private void handleAsyncJsonRequest(HttpService serviceInstance, RakamHttpRequest request, CompletionStage apply) {
        apply.whenComplete(new BiConsumer<Object, Throwable>() {
            @Override
            public void accept(Object result, Throwable ex) {
                if (ex != null) {
                    if (ex instanceof HttpRequestException) {
                        int statusCode = ((HttpRequestException) ex).getStatusCode();
                        String encode = encodeJson(errorMessage(ex.getMessage(), statusCode));
                        request.response(encode, HttpResponseStatus.valueOf(statusCode)).end();
                    } else {
                        request.response(ex.getMessage()).end();
                    }
                } else {
                    if(result instanceof String) {
                        request.response((String) result).end();
                    } else {
                        try {
                            String encode = mapper.writeValueAsString(result);
                            request.response(encode).end();
                        } catch (JsonProcessingException e) {
                            request.response(format("Couldn't serialize class %s : %s",
                                    result.getClass().getCanonicalName(), e.getMessage())).end();
                        }
                    }
                }
            }
        });
    }

    public void bind(String host, int port) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.option(ChannelOption.SO_BACKLOG, 1024);
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast("httpCodec", new HttpServerCodec());
                        p.addLast("serverHandler", new HttpServerHandler(routeMatcher));
                    }
                });

        channel = b.bind(host, port).sync().channel();
    }

    public void stop() {
        if (channel != null)
            channel.close().syncUninterruptibly();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    public void returnError(RakamHttpRequest request, String message, Integer statusCode) {
        ObjectNode obj = mapper.createObjectNode()
                .put("error", message)
                .put("error_code", statusCode);

        String bytes;
        try {
            bytes = mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException();
        }
        request.response(bytes, HttpResponseStatus.valueOf(statusCode))
                .headers().set("Content-Type", "application/json; charset=utf-8");
        request.end();
    }

    public static ObjectNode errorMessage(String message, int statusCode) {
        return DEFAULT_MAPPER.createObjectNode()
                .put("error", message)
                .put("error_code", statusCode);
    }

    public <T> void handleJsonPostRequest(RakamHttpRequest request, Consumer<T> consumer, Class<T> clazz) {
        request.bodyHandler(jsonStr -> {
            T data;
            try {
                data = mapper.readValue(jsonStr, clazz);
            } catch (IOException e) {
                returnError(request, "invalid request", 400);
                return;
            }
            consumer.accept(data);
        });
    }

    public static ObjectNode generate(Map<String, List<String>> map) {
        ObjectNode obj = jsonNodeFactory.objectNode();
        for (Map.Entry<String, List<String>> item : map.entrySet()) {
            String key = item.getKey();
            obj.put(key, item.getValue().get(0));
        }
        return obj;
    }

    private static final JsonNodeFactory jsonNodeFactory = new JsonNodeFactory(false);

}