package com.miniautomation.backend.crawler;

import com.miniautomation.backend.model.ButtonInfo;
import com.miniautomation.backend.model.FormInfo;
import com.miniautomation.backend.model.InputField;
import com.miniautomation.backend.model.LinkInfo;
import com.miniautomation.backend.model.PageInfo;
import com.miniautomation.backend.model.TableInfo;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

@Component
public class DomAnalyzer {

    public PageInfo analyze(String html) {

        Document document = Jsoup.parse(html);

        PageInfo pageInfo = new PageInfo();

        pageInfo.setTitle(document.title());
        pageInfo.setHtmlLength(html.length());

        extractForms(document, pageInfo);

        extractInputs(document, pageInfo);

        extractButtons(document, pageInfo);

        extractLinks(document, pageInfo);

        extractTables(document, pageInfo);

        return pageInfo;
    }

    private void extractForms(Document document, PageInfo pageInfo) {

        Elements forms = document.select("form");

        for (Element form : forms) {

            FormInfo formInfo = new FormInfo();

            formInfo.setId(form.id());
            formInfo.setAction(form.attr("action"));
            formInfo.setMethod(form.attr("method"));

            pageInfo.getForms().add(formInfo);
        }
    }

    private void extractInputs(Document document, PageInfo pageInfo) {

        Elements inputs = document.select("input");

        for (Element input : inputs) {

            InputField inputField = new InputField();

            inputField.setId(input.id());
            inputField.setName(input.attr("name"));
            inputField.setType(input.attr("type"));
            inputField.setPlaceholder(input.attr("placeholder"));
            inputField.setRequired(input.hasAttr("required"));

            pageInfo.getInputs().add(inputField);
        }
    }

    private void extractButtons(Document document, PageInfo pageInfo) {

        // HTML <button>
        Elements buttons = document.select("button");

        for (Element button : buttons) {

            ButtonInfo buttonInfo = new ButtonInfo();

            buttonInfo.setId(button.id());
            buttonInfo.setText(button.text());
            buttonInfo.setType("button");

            pageInfo.getButtons().add(buttonInfo);
        }

        // <input type="button|submit|reset">
        Elements inputButtons =
                document.select("input[type=button], input[type=submit], input[type=reset]");

        for (Element input : inputButtons) {

            ButtonInfo buttonInfo = new ButtonInfo();

            buttonInfo.setId(input.id());

            buttonInfo.setText(input.attr("value"));

            buttonInfo.setType(input.attr("type"));

            pageInfo.getButtons().add(buttonInfo);
        }

    }

    private void extractLinks(Document document, PageInfo pageInfo) {

        Elements links = document.select("a");

        for (Element link : links) {

            LinkInfo linkInfo = new LinkInfo();

            linkInfo.setText(link.text().trim());
            linkInfo.setHref(link.attr("href"));

            pageInfo.getLinks().add(linkInfo);
        }
    }

    private void extractTables(Document document, PageInfo pageInfo) {

        Elements tables = document.select("table");

        for (Element table : tables) {

            TableInfo tableInfo = new TableInfo();

            tableInfo.setId(table.id());

            Elements headers = table.select("th");
            for (Element header : headers) {
                tableInfo.getHeaders().add(header.text().trim());
            }

            Elements rows = table.select("tr");
            tableInfo.setRowCount(rows.size());
            tableInfo.setColumnCount(headers.size());

            pageInfo.getTables().add(tableInfo);
        }
    }

}