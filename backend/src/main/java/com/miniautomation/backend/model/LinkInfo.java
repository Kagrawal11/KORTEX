package com.miniautomation.backend.model;

public class LinkInfo {

    private String text;
    private String href;

    public LinkInfo() {
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }
}