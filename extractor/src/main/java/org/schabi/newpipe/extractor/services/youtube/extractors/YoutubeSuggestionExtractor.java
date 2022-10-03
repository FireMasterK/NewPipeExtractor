package org.schabi.newpipe.extractor.services.youtube.extractors;

import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getCookieHeader;
import static org.schabi.newpipe.extractor.utils.Utils.UTF_8;
import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.util.ArrayList;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/*
 * Created by Christian Schabesberger on 28.09.16.
 *
 * Copyright (C) Christian Schabesberger 2015 <chris.schabesberger@mailbox.org>
 * YoutubeSuggestionExtractor.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

public class YoutubeSuggestionExtractor extends SuggestionExtractor {

    public YoutubeSuggestionExtractor(final StreamingService service) {
        super(service);
    }

    @Override
    public List<String> suggestionList(final String query) throws IOException, ExtractionException {
        final String url = "https://suggestqueries-clients6.youtube.com/complete/search"
                + "?client=" + "youtube"
                + "&ds=" + "yt"
                + "&gl=" + Utils.encodeUrlUtf8(getExtractorContentCountry().getCountryCode())
                + "&q=" + Utils.encodeUrlUtf8(query)
                + "&xhr=t";

        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("Origin", Collections.singletonList("https://www.youtube.com"));
        headers.put("Referer", Collections.singletonList("https://www.youtube.com"));

        final Response response = NewPipe.getDownloader()
                .get(url, headers, getExtractorLocalization());

        final String contentTypeHeader = response.getHeader("Content-Type");
        if (isNullOrEmpty(contentTypeHeader) || !contentTypeHeader.contains("application/json")) {
            throw new ExtractionException("Invalid response type (got \""
                    + contentTypeHeader + "\", excepted a JSON response");
        }

        final String responseBody = response.responseBody();

        if (responseBody.isEmpty()) {
            throw new ExtractionException("Empty response received");
        }

        try {
            final JsonArray suggestions = JsonParser.array()
                    .from(responseBody)
                    .getArray(1); // 0: search query, 1: search suggestions, 2: tracking data?
            return suggestions.stream()
                    .filter(JsonArray.class::isInstance)
                    .map(JsonArray.class::cast)
                    .map(suggestion -> suggestion.getString(0)) // 0 is the search suggestion
                    .filter(Objects::nonNull)
                    .collect(Collectors.collectingAndThen(
                            Collectors.toList(), Collections::unmodifiableList));
        } catch (final JsonParserException e) {
            throw new ParsingException("Could not parse JSON response", e);
        }
    }
}
