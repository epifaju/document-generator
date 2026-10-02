package com.adgendoc.domain;

import java.util.Objects;

public class Template {

    private final String code;
    private final String version;
    private final String filePath;
    private final String checksum;
    private final boolean active;

    public Template(String code, String version, String filePath, String checksum,
                    boolean active) {
        this.code = Objects.requireNonNull(code, "code");
        this.version = Objects.requireNonNull(version, "version");
        this.filePath = Objects.requireNonNull(filePath, "filePath");
        this.checksum = Objects.requireNonNull(checksum, "checksum");
        this.active = active;
    }

    public String getCode() {
        return code;
    }

    public String getVersion() {
        return version;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getChecksum() {
        return checksum;
    }

    public boolean isActive() {
        return active;
    }
}
