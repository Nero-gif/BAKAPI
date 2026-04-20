package cz.nero.bakapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.nero.bakapi.model.GradeEntry;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public final class BakalariClient {
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0";
    private static final String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> TEACHER_KEYS = List.of(
            "teacher",
            "teacherName",
            "teachername",
            "ucitel",
            "jmenoUcitele",
            "jmenoUcitel",
            "udelil",
            "udelilText",
            "who"
    );

    public List<GradeEntry> fetchGrades(String baseUrl, String username, String password) throws IOException, InterruptedException {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        String normalizedUsername = requireValue(username, "Uživatelské jméno nesmí být prázdné.");
        String normalizedPassword = requireValue(password, "Heslo nesmí být prázdné.");

        HttpClient client = buildClient();
        loadLoginPage(client, normalizedBaseUrl);
        login(client, normalizedBaseUrl, normalizedUsername, normalizedPassword);

        HttpRequest gradesRequest = HttpRequest.newBuilder(URI.create(normalizedBaseUrl + "/next/prubzna.aspx"))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", normalizedBaseUrl + "/dashboard")
                .GET()
                .build();

        HttpResponse<String> gradesResponse = sendExpectSuccess(client, gradesRequest, "Nepodařilo se stáhnout stránku známek.");
        if (isLoginUri(gradesResponse.uri())) {
            throw new IOException("Server po přihlášení přesměroval zpět na login. Zkontroluj přihlašovací údaje.");
        }

        Map<String, String> teacherBySubject;
        try {
            teacherBySubject = fetchTeacherMapFromSubjectsPage(client, normalizedBaseUrl);
        } catch (IOException subjectsError) {
            try {
                teacherBySubject = fetchTeacherMapFromApi(normalizedBaseUrl, normalizedUsername, normalizedPassword);
            } catch (IOException apiError) {
                IOException combinedError = new IOException("Nepodařilo se načíst učitele předmětů ani z webu, ani z API.", apiError);
                combinedError.addSuppressed(subjectsError);
                throw combinedError;
            }
        }

        return parseGrades(gradesResponse.body(), teacherBySubject);
    }

    private static HttpClient buildClient() {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    private static void loadLoginPage(HttpClient client, String baseUrl) throws IOException, InterruptedException {
        HttpRequest loginPageRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/login"))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT)
                .GET()
                .build();
        sendExpectSuccess(client, loginPageRequest, "Nepodařilo se otevřít login stránku.");
    }

    private static void login(HttpClient client, String baseUrl, String username, String password) throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", username);
        form.put("password", password);
        form.put("returnUrl", "");

        HttpRequest loginRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/Login"))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
                .build();

        HttpResponse<String> loginResponse = sendExpectSuccess(client, loginRequest, "Přihlášení selhalo.");
        if (isLoginUri(loginResponse.uri())) {
            throw new IOException("Přihlášení se nepovedlo. Server stále vrací login stránku.");
        }
    }

    private static HttpResponse<String> sendExpectSuccess(HttpClient client, HttpRequest request, String errorMessage)
            throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException(errorMessage + " HTTP " + statusCode + ".");
        }
        return response;
    }

    private static boolean isLoginUri(URI uri) {
        String path = uri.getPath();
        return path != null && path.toLowerCase().contains("login");
    }

    private static List<GradeEntry> parseGrades(String html, Map<String, String> teacherBySubject) throws IOException {
        Document document = Jsoup.parse(html);
        Elements marks = document.select(".predmet-radek .znamka-v, .predmet-radek .znamka-h");
        List<GradeEntry> grades = new ArrayList<>();

        for (Element mark : marks) {
            String dataClasif = mark.attr("data-clasif");
            JsonNode data = parseGradeData(dataClasif);

            String sourceId = getSourceId(data);
            String subject = textOrDefault(data.get("nazev"), "Neznámý předmět");
            String teacher = getTeacher(data, mark, teacherBySubject, subject);
            String markText = textOrDefault(data.get("MarkText"), "?");
            String caption = textOrDefault(data.get("caption"), "Bez tématu");
            String date = textOrDefault(data.get("strdatum"), "Bez data");
            String weight = textOrDefault(data.get("vaha"), "");
            String note = cleanNote(textOrDefault(data.get("poznamkakzobrazeni"), ""));

            grades.add(new GradeEntry(sourceId, subject, teacher, markText, caption, note, weight, date, ""));
        }

        return List.copyOf(grades);
    }

    private static JsonNode parseGradeData(String dataClasif) throws IOException {
        String normalized = Objects.requireNonNullElse(dataClasif, "").trim();
        if (normalized.isEmpty()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            return OBJECT_MAPPER.readTree(normalized);
        } catch (IOException e) {
            throw new IOException("Nepodařilo se zpracovat data známky z atributu data-clasif.", e);
        }
    }

    private static String cleanNote(String noteHtml) {
        if (noteHtml.isBlank()) {
            return "";
        }
        return Jsoup.parse(noteHtml).text().trim();
    }

    private static String getSourceId(JsonNode data) {
        String[] keys = {"id", "ID", "Id", "classificationId", "ClassifId", "klasifikaceId", "ClassificationId"};
        for (String key : keys) {
            String value = textOrDefault(data.get(key), "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String getTeacher(JsonNode data, Element markElement, Map<String, String> teacherBySubject, String subject) {
        String teacherFromSubjectMap = teacherBySubject.getOrDefault(normalizeSubjectKey(subject), "");
        if (!teacherFromSubjectMap.isBlank()) {
            return teacherFromSubjectMap;
        }

        String teacherFromSubjectRow = getTeacherFromSubjectRow(markElement);
        if (!teacherFromSubjectRow.isBlank()) {
            return teacherFromSubjectRow;
        }

        for (String key : TEACHER_KEYS) {
            String value = textOrDefault(data.get(key), "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String getTeacherFromSubjectRow(Element markElement) {
        Element subjectRow = markElement.closest(".predmet-radek");
        if (subjectRow == null) {
            return "";
        }

        Element teacherElement = subjectRow.selectFirst(".ucitel, [class*=ucitel], .teacher, [class*=teacher]");
        if (teacherElement == null) {
            return "";
        }

        return teacherElement.text().trim();
    }

    private static Map<String, String> fetchTeacherMapFromSubjectsPage(HttpClient client, String baseUrl)
            throws IOException, InterruptedException {
        List<String> candidatePaths = List.of("/next/predmety.aspx", "/next/subjects.aspx");
        List<String> errors = new ArrayList<>();

        for (String path : candidatePaths) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", ACCEPT)
                    .header("Referer", baseUrl + "/dashboard")
                    .GET()
                    .build();

            try {
                HttpResponse<String> response = sendExpectSuccess(client, request, "Nepodařilo se načíst stránku " + path + ".");
                if (isLoginUri(response.uri())) {
                    errors.add(path + ": přesměrování na login");
                    continue;
                }

                Map<String, String> teacherMap = parseTeacherMapFromSubjectsHtml(response.body());
                if (!teacherMap.isEmpty()) {
                    return teacherMap;
                }

                errors.add(path + ": nenalezeny dvojice předmět/učitel");
            } catch (IOException e) {
                errors.add(path + ": " + e.getMessage());
            }
        }

        throw new IOException("Nepodařilo se získat učitele ze stránek předmětů. " + String.join(" | ", errors));
    }

    private static Map<String, String> fetchTeacherMapFromApi(String baseUrl, String username, String password)
            throws IOException, InterruptedException {
        HttpClient apiClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();

        Map<String, String> loginForm = new LinkedHashMap<>();
        loginForm.put("client_id", "ANDR");
        loginForm.put("grant_type", "password");
        loginForm.put("username", username);
        loginForm.put("password", password);

        HttpRequest loginRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/login"))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(loginForm)))
                .build();

        HttpResponse<String> loginResponse = sendExpectSuccess(apiClient, loginRequest, "Nepodařilo se přihlásit do API.");
        JsonNode loginJson = OBJECT_MAPPER.readTree(loginResponse.body());
        String accessToken = textOrDefault(loginJson.get("access_token"), "");
        if (accessToken.isBlank()) {
            throw new IOException("API login nevrátil access token.");
        }

        HttpRequest subjectsRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/3/subjects"))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> subjectsResponse = sendExpectSuccess(apiClient, subjectsRequest, "Nepodařilo se stáhnout /api/3/subjects.");
        Map<String, String> teacherMap = parseTeacherMapFromApiJson(subjectsResponse.body());
        if (teacherMap.isEmpty()) {
            throw new IOException("API /api/3/subjects neobsahuje mapu předmět -> učitel.");
        }

        return teacherMap;
    }

    private static Map<String, String> parseTeacherMapFromSubjectsHtml(String html) {
        Document document = Jsoup.parse(html);
        Map<String, String> teacherMap = new LinkedHashMap<>();

        for (Element row : document.select("tr")) {
            Elements cells = row.select("> th, > td");
            if (cells.size() < 2) {
                continue;
            }

            String subject = cells.get(0).text().trim();
            String teacher = cells.get(1).text().trim();
            putTeacher(teacherMap, subject, teacher);
        }

        for (Element card : document.select(".predmet-radek, .predmet, .subject, [class*=predmet]")) {
            Element subjectElement = card.selectFirst(".nazev, [class*=nazev], .subject-name, .predmet-nazev");
            Element teacherElement = card.selectFirst(".ucitel, [class*=ucitel], .teacher, [class*=teacher]");
            if (subjectElement == null || teacherElement == null) {
                continue;
            }

            putTeacher(teacherMap, subjectElement.text().trim(), teacherElement.text().trim());
        }

        return teacherMap;
    }

    private static Map<String, String> parseTeacherMapFromApiJson(String json) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(json);
        JsonNode subjectsNode = root.get("Subjects");
        if (subjectsNode == null || !subjectsNode.isArray()) {
            subjectsNode = root.get("subjects");
        }
        if (subjectsNode == null || !subjectsNode.isArray()) {
            return Map.of();
        }

        Map<String, String> teacherMap = new LinkedHashMap<>();
        for (JsonNode subjectNode : subjectsNode) {
            String subject = firstNonBlank(
                    textOrDefault(subjectNode.get("SubjectName"), ""),
                    textOrDefault(subjectNode.get("subjectName"), ""),
                    textOrDefault(subjectNode.get("Name"), ""),
                    textOrDefault(subjectNode.get("name"), "")
            );
            String teacher = firstNonBlank(
                    textOrDefault(subjectNode.get("TeacherName"), ""),
                    textOrDefault(subjectNode.get("teacherName"), ""),
                    textOrDefault(subjectNode.get("Teacher"), ""),
                    textOrDefault(subjectNode.get("teacher"), "")
            );
            putTeacher(teacherMap, subject, teacher);
        }

        return teacherMap;
    }

    private static void putTeacher(Map<String, String> teacherMap, String subject, String teacher) {
        String normalizedSubject = normalizeSubjectKey(subject);
        String normalizedTeacher = normalizeTeacher(teacher);
        if (normalizedSubject.isBlank() || normalizedTeacher.isBlank()) {
            return;
        }
        if (normalizedSubject.contains("předmět") && normalizedTeacher.contains("učitel")) {
            return;
        }
        teacherMap.put(normalizedSubject, normalizedTeacher);
    }

    private static String normalizeSubjectKey(String subject) {
        if (subject == null) {
            return "";
        }
        return subject
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizeTeacher(String teacher) {
        if (teacher == null) {
            return "";
        }
        return teacher
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String textOrDefault(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? fallback : value;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String normalized = requireValue(baseUrl, "URL adresa Bakalářů nesmí být prázdná.");
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("URL musí začínat na http:// nebo https://");
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static String requireValue(String value, String errorMessage) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return value.trim();
    }

    private static String encodeForm(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
