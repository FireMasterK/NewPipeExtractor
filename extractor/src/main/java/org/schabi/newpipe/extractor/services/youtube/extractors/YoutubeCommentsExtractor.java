package org.schabi.newpipe.extractor.services.youtube.extractors;

import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareJsonBuilder;
import static org.schabi.newpipe.extractor.utils.Utils.UTF_8;
import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nonnull;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItemExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItemsCollector;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.utils.JsonUtils;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

public class YoutubeCommentsExtractor extends CommentsExtractor {

    private JsonObject nextResponse;

    /**
     * Caching mechanism and holder of the commentsDisabled value.
     * <br/>
     * Initial value = empty -> unknown if comments are disabled or not<br/>
     * Some method calls {@link YoutubeCommentsExtractor#findInitialCommentsToken()}
     * -> value is set<br/>
     * If the method or another one that is depending on disabled comments
     * is now called again, the method execution can avoid unnecessary calls
     */
    private Optional<Boolean> optCommentsDisabled = Optional.empty();

    public YoutubeCommentsExtractor(
            final StreamingService service,
            final ListLinkHandler uiHandler) {
        super(service, uiHandler);
    }

    @Override
    public InfoItemsPage<CommentsInfoItem> getInitialPage()
            throws IOException, ExtractionException {

        // Check if findInitialCommentsToken was already called and optCommentsDisabled initialized
        if (optCommentsDisabled.orElse(false)) {
            return getInfoItemsPageForDisabledComments();
        }

        // Get the token
        final String commentsToken = findInitialCommentsToken();
        // Check if the comments have been disabled
        if (optCommentsDisabled.get()) {
            return getInfoItemsPageForDisabledComments();
        }

        return getPage(getNextPage(commentsToken));
    }

    /**
     * Finds the initial comments token and initializes commentsDisabled.
     *
     * @return the continuation token or null if none was found
     */
    private String findInitialCommentsToken() throws ExtractionException {

        final JsonArray jArray = JsonUtils.getArray(nextResponse, "contents.twoColumnWatchNextResults.results.results.contents");

        Optional<Object> itemSectionRenderer = jArray.stream().filter(o -> {
            JsonObject jObj = (JsonObject) o;

            if (jObj.has("itemSectionRenderer")) {
                try {
                    return JsonUtils.getString(jObj, "itemSectionRenderer.targetId").equals("comments-section");
                } catch (ParsingException i) {
                }
            }

            return false;
        }).findFirst();

        final String token;

        if (itemSectionRenderer.isPresent())
            token = JsonUtils.getString(((JsonObject) itemSectionRenderer.get()).getObject("itemSectionRenderer").getArray("contents").getObject(0), "continuationItemRenderer.continuationEndpoint.continuationCommand.token");
        else token = null;

        if (token == null) {
            optCommentsDisabled = Optional.of(true);
            return null;
        }

        optCommentsDisabled = Optional.of(false);

        return token;
    }

    private InfoItemsPage<CommentsInfoItem> getInfoItemsPageForDisabledComments() {
        return new InfoItemsPage<>(Collections.emptyList(), null, Collections.emptyList());
    }

    private Page getNextPage(final JsonObject ajaxJson) throws ExtractionException {
        final JsonArray arr;
        final JsonArray onResponseReceivedEndpoints = ajaxJson.getArray("onResponseReceivedEndpoints");
        final JsonObject endpoint = onResponseReceivedEndpoints.getObject(onResponseReceivedEndpoints.size() - 1);
        try {
            arr = endpoint.getObject("reloadContinuationItemsCommand", endpoint.getObject("appendContinuationItemsAction")).getArray("continuationItems");
        } catch (final Exception e) {
            return null;
        }
        if (arr.isEmpty()) {
            return null;
        }
        final String continuation;
        try {
            continuation = JsonUtils.getString(arr.getObject(arr.size() - 1), "continuationItemRenderer.continuationEndpoint.continuationCommand.token");
        } catch (final Exception e) {
            e.printStackTrace();
            return null;
        }
        return getNextPage(continuation);
    }

    private Page getNextPage(final String continuation) throws ParsingException {
        return new Page(getUrl(), continuation); // URL is ignored tho
    }

    @Override
    public InfoItemsPage<CommentsInfoItem> getPage(final Page page) throws IOException, ExtractionException {
        if (optCommentsDisabled.orElse(false)) {
            return getInfoItemsPageForDisabledComments();
        }
        if (page == null || isNullOrEmpty(page.getId())) {
            throw new IllegalArgumentException("Page doesn't have the continuation.");
        }

        final Localization localization = getExtractorLocalization();
        final ContentCountry contentCountry = getExtractorContentCountry();

        final byte[] body = JsonWriter.string(prepareJsonBuilder(localization, contentCountry)
                .value("videoId", getId())
                .value("continuation", page.getId())
                .done())
                .getBytes(UTF_8);

        final JsonObject ajaxJson = getJsonPostResponse("next", body, localization);

        final CommentsInfoItemsCollector collector = new CommentsInfoItemsCollector(getServiceId());
        collectCommentsFrom(collector, ajaxJson);
        return new InfoItemsPage<>(collector, getNextPage(ajaxJson));
    }

    private void collectCommentsFrom(final CommentsInfoItemsCollector collector, final JsonObject ajaxJson) throws ParsingException {

        final JsonArray onResponseReceivedEndpoints = ajaxJson.getArray("onResponseReceivedEndpoints");
        final JsonObject commentsEndpoint = onResponseReceivedEndpoints.getObject(onResponseReceivedEndpoints.size() - 1);

        String path = null;

        if (commentsEndpoint.has("reloadContinuationItemsCommand"))
            path = "reloadContinuationItemsCommand.continuationItems";
        else if (commentsEndpoint.has("appendContinuationItemsAction"))
            path = "appendContinuationItemsAction.continuationItems";

        final JsonArray contents;
        try {
            contents = (JsonArray) JsonUtils.getArray(commentsEndpoint, path).clone();
        } catch (final Exception e) {
            e.printStackTrace();
            //no comments
            return;
        }

        final int index = contents.size() - 1;
        if (contents.getObject(index).has("continuationItemRenderer"))
            contents.remove(index);

        final List<Object> comments;
        try {
            comments = JsonUtils.getValues(contents, "commentThreadRenderer.comment.commentRenderer");
        } catch (final Exception e) {
            throw new ParsingException("unable to get parse youtube comments", e);
        }

        for (final Object c : comments) {
            if (c instanceof JsonObject) {
                final CommentsInfoItemExtractor extractor =
                        new YoutubeCommentsInfoItemExtractor((JsonObject) c, getUrl(), getTimeAgoParser());
                collector.commit(extractor);
            }
        }
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) throws IOException, ExtractionException {

        final Localization localization = getExtractorLocalization();
        final ContentCountry contentCountry = getExtractorContentCountry();

        final byte[] body = JsonWriter.string(prepareJsonBuilder(localization, contentCountry)
                .value("videoId", getId())
                .done())
                .getBytes(UTF_8);

        nextResponse = getJsonPostResponse("next", body, localization);
    }


    @Override
    public boolean isCommentsDisabled() throws ExtractionException {
        // Check if commentsDisabled has to be initialized
        if (!optCommentsDisabled.isPresent()) {
            // Initialize commentsDisabled
            this.findInitialCommentsToken();
        }

        return optCommentsDisabled.get();
    }
}
