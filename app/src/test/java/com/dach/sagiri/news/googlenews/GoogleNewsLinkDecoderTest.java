package com.dach.sagiri.news.googlenews;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.dach.sagiri.news.HttpFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Google News stub decoder against a mocked {@link HttpFetcher}: the real
 * response shape, none of the requests.
 */
class GoogleNewsLinkDecoderTest {

    private static final String ARTICLE_ID = "CBMiT0FVX3lxTE5";
    private static final String STUB = "https://news.google.com/rss/articles/" + ARTICLE_ID
                                       + "?oc=5";
    private static final String TIMESTAMP = "1758300000";
    private static final String SIGNATURE = "AOyNcsAbc123";
    private static final String PUBLISHER = "https://www.fool.com/investing/2026/09/06/story";

    /** The wire form: anti-hijacking prefix, chunk lengths and JSON inside JSON. */
    private static String answer(String url) {
        return """
            )]}'

            352
            [["wrb.fr","Fbv4je","[\\"garturlres\\",\\"%s\\",1]",null,null,null,"generic"],["di",11]]
            26
            [["e",4,null,null,331]]
            """.formatted(url);
    }

    private static final String ANSWER = answer(PUBLISHER);

    private static final String STUB_PAGE = """
        <!doctype html><html><body><c-wiz>
        <a class="WwrzSb" data-n-a-ts="%s" data-n-a-sg="%s" href="./read/..."></a>
        </c-wiz></body></html>""".formatted(TIMESTAMP, SIGNATURE);

    private HttpFetcher fetcher;
    private GoogleNewsLinkDecoder decoder;

    @BeforeEach
    void setUp() {
        fetcher = mock(HttpFetcher.class);
        decoder = new GoogleNewsLinkDecoder(fetcher);
    }

    private void stubPage(String html) {
        when(fetcher.getPage(anyString()))
            .thenReturn(Optional.of(html.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void turns_a_stub_into_the_publisher_url() {
        stubPage(STUB_PAGE);
        when(fetcher.postForm(anyString(), anyString())).thenReturn(Optional.of(ANSWER));

        assertThat(decoder.decode(STUB)).contains(PUBLISHER);
        verify(fetcher).getPage(STUB);
    }

    @Test
    void posts_the_article_id_with_the_signature_taken_from_the_page() throws Exception {
        stubPage(STUB_PAGE);
        when(fetcher.postForm(anyString(), anyString())).thenReturn(Optional.of(ANSWER));

        decoder.decode(STUB);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(fetcher).postForm(url.capture(), body.capture());

        assertThat(url.getValue()).contains("batchexecute").contains("rpcids=Fbv4je");
        assertThat(body.getValue()).startsWith("f.req=");
        // f.req is a JSON envelope whose second element is itself JSON as a string.
        JsonNode call = new ObjectMapper()
            .readTree(URLDecoder.decode(body.getValue().substring("f.req=".length()),
                StandardCharsets.UTF_8))
            .path(0).path(0);
        assertThat(call.path(0).asText()).isEqualTo("Fbv4je");
        assertThat(call.path(3).asText()).isEqualTo("generic");
        assertThat(call.path(1).asText())
            .startsWith("[\"garturlreq\"")
            .endsWith("\"%s\",%s,\"%s\"]".formatted(ARTICLE_ID, TIMESTAMP, SIGNATURE));
    }

    @Test
    void a_link_without_an_article_segment_is_not_worth_a_request() {
        assertThat(decoder.decode("https://news.google.com/read/CBMiT0FVX3lxTE5")).isEmpty();
        assertThat(decoder.decode("https://bioworld.example/gene")).isEmpty();
        assertThat(decoder.decode(null)).isEmpty();
        verifyNoInteractions(fetcher);
    }

    @Test
    void an_article_path_on_another_host_is_not_a_google_stub() {
        assertThat(decoder.decode("https://bioworld.example/rss/articles/CBMiT0FVX3lxTE5"))
            .isEmpty();
        verifyNoInteractions(fetcher);
    }

    @Test
    void decodes_a_stub_that_does_not_go_through_rss() {
        stubPage(STUB_PAGE);
        when(fetcher.postForm(anyString(), anyString())).thenReturn(Optional.of(ANSWER));

        assertThat(decoder.decode("https://news.google.com/articles/" + ARTICLE_ID))
            .contains(PUBLISHER);
    }

    @Test
    void the_timestamp_and_the_signature_must_belong_to_one_link() {
        stubPage("""
            <html><body>
            <a data-n-a-ts="%s"></a>
            <a data-n-a-sg="%s"></a>
            </body></html>""".formatted(TIMESTAMP, SIGNATURE));

        assertThat(decoder.decode(STUB)).isEmpty();
        verify(fetcher, never()).postForm(anyString(), anyString());
    }

    @Test
    void the_attribute_order_on_the_link_does_not_matter() {
        stubPage("""
            <html><body><a data-n-a-sg="%s" class="WwrzSb" data-n-a-ts="%s"></a></body></html>"""
            .formatted(SIGNATURE, TIMESTAMP));
        when(fetcher.postForm(anyString(), anyString())).thenReturn(Optional.of(ANSWER));

        assertThat(decoder.decode(STUB)).contains(PUBLISHER);
    }

    @Test
    void a_decoded_link_that_is_still_google_is_refused() {
        stubPage(STUB_PAGE);
        when(fetcher.postForm(anyString(), anyString()))
            .thenReturn(Optional.of(answer("https://news.google.com/read/CBMiT0FVX3lxTE5")));

        assertThat(decoder.decode(STUB)).isEmpty();
    }

    @Test
    void a_page_without_a_signature_is_not_posted() {
        stubPage("<html><body><a data-n-a-ts=\"1758300000\"></a></body></html>");

        assertThat(decoder.decode(STUB)).isEmpty();
        verify(fetcher, never()).postForm(anyString(), anyString());
    }

    @Test
    void an_unreadable_stub_page_is_not_posted() {
        when(fetcher.getPage(anyString())).thenReturn(Optional.empty());

        assertThat(decoder.decode(STUB)).isEmpty();
        verify(fetcher, never()).postForm(anyString(), anyString());
    }

    @Test
    void a_failed_post_leaves_the_stub_in_place() {
        stubPage(STUB_PAGE);
        when(fetcher.postForm(anyString(), anyString())).thenReturn(Optional.empty());

        assertThat(decoder.decode(STUB)).isEmpty();
    }

    @Test
    void a_broken_answer_yields_no_link_instead_of_an_exception() {
        stubPage(STUB_PAGE);

        when(fetcher.postForm(anyString(), anyString()))
            .thenReturn(Optional.of("<html>Sorry, an error occurred.</html>"));
        assertThat(decoder.decode(STUB)).as("no JSON at all").isEmpty();

        when(fetcher.postForm(anyString(), anyString()))
            .thenReturn(Optional.of(")]}'\n\n[[\"wrb.fr\",\"Fbv4je\",\"[\\\"gartu"));
        assertThat(decoder.decode(STUB)).as("truncated JSON").isEmpty();

        when(fetcher.postForm(anyString(), anyString()))
            .thenReturn(Optional.of(")]}'\n\n[[\"wrb.fr\",\"Oxyz9\",\"[]\",null]]"));
        assertThat(decoder.decode(STUB)).as("another rpc id").isEmpty();
    }

    @Test
    void an_answer_that_is_not_a_url_is_refused() {
        stubPage(STUB_PAGE);
        when(fetcher.postForm(anyString(), anyString()))
            .thenReturn(Optional.of(ANSWER.replace(PUBLISHER, "ERROR_CODE_42")));

        assertThat(decoder.decode(STUB)).isEmpty();
    }
}
