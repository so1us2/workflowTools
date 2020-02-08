package com.vmware.reviewboard.domain;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.stream.Collectors;

public class LinkArrayDeserializer implements JsonDeserializer<String> {

    @Override
    public String deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        Link[] links = jsonDeserializationContext.deserialize(jsonElement, Link[].class);
        if (links == null) {
            return null;
        }
        return Arrays.stream(links).map(Link::getTitle).collect(Collectors.joining(","));
    }
}
