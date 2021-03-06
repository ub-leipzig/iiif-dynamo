/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.ubleipzig.dynamo.camel;

import static de.ubleipzig.webanno.AbstractSerializer.serialize;
import static java.net.URLEncoder.encode;
import static org.apache.camel.Exchange.CONTENT_TYPE;
import static org.apache.camel.Exchange.HTTP_CHARACTER_ENCODING;
import static org.apache.camel.Exchange.HTTP_METHOD;
import static org.apache.camel.Exchange.HTTP_RESPONSE_CODE;
import static org.apache.camel.LoggingLevel.INFO;

import de.ubleipzig.dynamo.JsonLdProcessorUtils;
import de.ubleipzig.dynamo.QueryUtils;
import de.ubleipzig.webanno.AnnotationListBuilder;
import de.ubleipzig.webanno.templates.AnnotationList;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.main.Main;
import org.apache.camel.main.MainListenerSupport;
import org.apache.camel.main.MainSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Dynamo.
 *
 * @author christopher-johnson
 */
public class DynamoSearch {

    /**
     * @param args String[]
     * @throws Exception Exception
     */
    public static void main(final String[] args) throws Exception {
        final DynamoSearch selector = new DynamoSearch();
        selector.init();
    }

    /**
     * @throws Exception Exception
     */
    private void init() throws Exception {
        final Main main = new Main();
        main.addRouteBuilder(new QueryRoute());
        main.addMainListener(new Events());
        main.setPropertyPlaceholderLocations("file:${env:DYNAMO_HOME}/de.ubleipzig.dynamo.cfg");
        main.run();
    }

    /**
     * Events.
     */
    public static class Events extends MainListenerSupport {

        @Override
        public void afterStart(final MainSupport main) {
            System.out.println("Dynamo Search is now started!");
        }

        @Override
        public void beforeStop(final MainSupport main) {
            System.out.println("Dynamo Search is now being stopped!");
        }
    }

    /**
     * QueryRoute.
     */
    public static class QueryRoute extends RouteBuilder {

        private static final Logger LOGGER = LoggerFactory.getLogger(DynamoSearch.class);

        private static final String contentTypeNTriples = "application/n-triples";
        private static final String contentTypeJsonLd = "application/ld+json";
        private static final String contentTypeHTMLForm = "application/x-www-form-urlencoded";
        private static final String EMPTY = "empty";
        private static final String HTTP_ACCEPT = "Accept";
        private static final String V1_SET = "q";

        /**
         * @param e Exchange
         * @return String
         */
        private static String getV1(final Exchange e) {
            final Object optHdr = e.getIn()
                                   .getHeader(V1_SET);
            return (String) optHdr;
        }

        /**
         * configure.
         */
        public void configure() {

            from("jetty:http://{{searchservice.host}}:{{searchservice.port}}{{searchservice.prefix}}?"
                    + "optionsEnabled=true&matchOnUriPrefix=true&sendServerVersion=false"
                    + "&httpMethodRestrict=GET,OPTIONS")
                    .routeId("Sparqler")
                    .removeHeaders(HTTP_ACCEPT)
                    .setHeader("Access-Control-Allow-Origin")
                    .constant("*")
                    .choice()
                    .when(header(HTTP_METHOD).isEqualTo("GET"))
                    .to("direct:sparql");
            from("direct:sparql")
                    .routeId("SparqlerGet")
                    .setHeader(HTTP_METHOD)
                    .constant("POST")
                    .setHeader(CONTENT_TYPE)
                    .constant(contentTypeHTMLForm + "; charset=utf-8")
                    .setHeader(HTTP_ACCEPT)
                    .constant(contentTypeNTriples)
                    .process(e -> e.getIn()
                                   .setBody(sparqlConstruct(
                                           QueryUtils.getQuery("searchanno.sparql",  getV1(e)))))
                    .log(INFO, LOGGER, String.valueOf(body()))
                    .to("http4:{{triplestore.baseUrl}}?useSystemProperties=true&bridgeEndpoint=true")
                    .filter(header(HTTP_RESPONSE_CODE).isEqualTo(200))
                    .setHeader(CONTENT_TYPE)
                    .constant(contentTypeNTriples)
                    .convertBodyTo(String.class)
                    .log(INFO, LOGGER, "Getting query results as n-triples")
                    .to("direct:toJsonLd");
            from("direct:toJsonLd")
                    .routeId("JsonLd")
                    .log(INFO, LOGGER, "Marshalling RDF to Json-Ld")
                    .process(e -> {
                        final String body = e.getIn().getBody().toString();
                        if ( body != null && !body.isEmpty()) {
                            final String contextUri = "context.json";
                            final String frameUri = "searchanno-frame.json";
                            e.getIn().setBody(JsonLdProcessorUtils.toJsonLd(e.getIn().getBody().toString(), contextUri,
                                    frameUri));
                        } else {
                            e.getIn().setHeader(CONTENT_TYPE, EMPTY);
                        }
                    })
                    .removeHeader(HTTP_ACCEPT)
                    .choice()
                    .when(header(CONTENT_TYPE).isEqualTo(EMPTY))
                    .to("direct:buildEmptyList")
                    .otherwise()
                    .to("direct:buildAnnotationList");
            from("direct:buildEmptyList")
                    .routeId("EmptyListBuilder")
                    .process(e -> {
                        final AnnotationList emptyList = new AnnotationList();
                        e.getIn().setBody(serialize(emptyList).orElse(""));
                    });
            from("direct:buildAnnotationList")
                    .routeId("AnnotationListBuilder")
                    .setHeader(HTTP_CHARACTER_ENCODING)
                    .constant("UTF-8")
                    .setHeader(CONTENT_TYPE)
                    .constant(contentTypeJsonLd)
                    .log(INFO, LOGGER, "Building Collection")
                    .process(e -> {
                        final AnnotationListBuilder builder = new AnnotationListBuilder(e.getIn()
                                                                                         .getBody()
                                                                                         .toString());
                        e.getIn()
                         .setBody(builder.build());
                    });
        }

        /**
         * @param command String
         * @return String
         */
        private static String sparqlConstruct(final String command) {
            try {
                return "query=" + encode(command, "UTF-8");
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }
}
