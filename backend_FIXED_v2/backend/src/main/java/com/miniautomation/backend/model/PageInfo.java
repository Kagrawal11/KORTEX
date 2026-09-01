package com.miniautomation.backend.model;

import java.util.ArrayList;
import java.util.List;

public class PageInfo {

    private String title;
    private String url;
    private int htmlLength;

    private List<FormInfo> forms = new ArrayList<>();
    private List<InputField> inputs = new ArrayList<>();
    private List<ButtonInfo> buttons = new ArrayList<>();
    private List<LinkInfo> links = new ArrayList<>();
    private List<TableInfo> tables = new ArrayList<>();

    public PageInfo() {
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getHtmlLength() {
        return htmlLength;
    }

    public void setHtmlLength(int htmlLength) {
        this.htmlLength = htmlLength;
    }

    public List<FormInfo> getForms() {
        return forms;
    }

    public void setForms(List<FormInfo> forms) {
        this.forms = forms;
    }

    public List<InputField> getInputs() {
        return inputs;
    }

    public void setInputs(List<InputField> inputs) {
        this.inputs = inputs;
    }

    public List<ButtonInfo> getButtons() {
        return buttons;
    }

    public void setButtons(List<ButtonInfo> buttons) {
        this.buttons = buttons;
    }

    public List<LinkInfo> getLinks() {
        return links;
    }

    public void setLinks(List<LinkInfo> links) {
        this.links = links;
    }

    public List<TableInfo> getTables() {
        return tables;
    }

    public void setTables(List<TableInfo> tables) {
        this.tables = tables;
    }
}