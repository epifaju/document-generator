package com.adgendoc.application;

import com.adgendoc.domain.GeneratedDocument;

import java.util.Objects;

public record DocumentContent(GeneratedDocument metadata, byte[] bytes) {

    public DocumentContent {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(bytes, "bytes");
    }
}
