package cz.nero.bakapi.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

public final class ConsultationHoursService {
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}:\\d{2})\\s*[\\-–]\\s*(\\d{1,2}:\\d{2})");
    private static final List<String> DAY_ORDER = List.of("PO", "ÚT", "ST", "ČT", "PÁ");
    private static final Set<String> TITLE_TOKENS = Set.of(
            "mgr", "ing", "bc", "rndr", "doc", "phd", "mudr", "mvdr", "judr", "paeddr", "dis", "mba"
    );
    private static final Set<String> ROLE_HINT_TOKENS = Set.of(
            "reditel", "poradkyne", "poradce", "metodik", "prevence", "projekty", "inovace", "vyucovani", "kar", "zr", "ovy"
    );

    public List<ConsultationHours> loadFromPdf(Path pdfPath) throws IOException {
        if (pdfPath == null || !Files.exists(pdfPath)) {
            throw new IOException("Vybraný PDF soubor neexistuje.");
        }
        String filename = pdfPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".pdf")) {
            throw new IOException("Vybraný soubor není PDF.");
        }

        List<ConsultationHours> allRows = new ArrayList<>();
        TableLayout knownLayout = null;
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                List<Word> words = extractWordsForPage(document, page);
                TableLayout pageLayout = detectLayout(words);
                if (pageLayout != null) {
                    knownLayout = pageLayout;
                    allRows.addAll(parsePageRows(words, pageLayout, true));
                } else if (knownLayout != null) {
                    allRows.addAll(parsePageRows(words, knownLayout, false));
                }
            }
        }

        Map<String, ConsultationHours> mergedByName = new LinkedHashMap<>();
        for (ConsultationHours row : allRows) {
            String key = normalizedNameKey(row.teacherName());
            if (key.isBlank()) {
                continue;
            }
            ConsultationHours existing = mergedByName.get(key);
            if (existing == null) {
                mergedByName.put(key, row);
            } else {
                Map<String, String> mergedSchedule = new LinkedHashMap<>(existing.scheduleByDay());
                for (String day : DAY_ORDER) {
                    String currentValue = safeTrim(mergedSchedule.get(day));
                    String newValue = safeTrim(row.scheduleByDay().get(day));
                    if (!newValue.isBlank() && currentValue.isBlank()) {
                        mergedSchedule.put(day, newValue);
                    }
                }
                mergedByName.put(key, new ConsultationHours(existing.teacherName(), Map.copyOf(mergedSchedule)));
            }
        }

        List<ConsultationHours> result = new ArrayList<>(mergedByName.values());
        result.sort(Comparator.comparing(item -> item.teacherName().toLowerCase(Locale.ROOT)));
        return List.copyOf(result);
    }

    public String findConsultationForTeacher(String teacherName, List<ConsultationHours> consultationHours) {
        if (consultationHours == null || consultationHours.isEmpty()) {
            return "";
        }

        NormalizedTeacherName teacher = normalizeTeacherName(teacherName);
        if (teacher.tokens().isEmpty()) {
            return "";
        }

        Map<String, ConsultationHours> byExactName = new HashMap<>();
        Map<String, List<ConsultationHours>> byToken = new HashMap<>();
        for (ConsultationHours hours : consultationHours) {
            NormalizedTeacherName normalizedHoursTeacher = normalizeTeacherName(hours.teacherName());
            String fullKey = normalizedHoursTeacher.joinedTokens();
            if (!fullKey.isBlank()) {
                byExactName.putIfAbsent(fullKey, hours);
            }
            for (String token : normalizedHoursTeacher.tokens()) {
                if (token.length() < 2) {
                    continue;
                }
                byToken.computeIfAbsent(token, key -> new ArrayList<>()).add(hours);
            }
        }

        ConsultationHours exact = byExactName.get(teacher.joinedTokens());
        if (exact != null) {
            return exact.formatForDisplay();
        }

        ConsultationHours best = null;
        int bestScore = 0;
        boolean ambiguous = false;
        for (ConsultationHours candidate : consultationHours) {
            int score = scoreMatch(teacher, normalizeTeacherName(candidate.teacherName()));
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                ambiguous = false;
            } else if (score > 0 && score == bestScore) {
                ambiguous = true;
            }
        }

        if (!ambiguous && best != null && bestScore >= 3) {
            return best.formatForDisplay();
        }

        Set<ConsultationHours> tokenCandidates = new HashSet<>();
        for (String token : teacher.tokens()) {
            List<ConsultationHours> matches = byToken.get(token);
            if (matches != null) {
                tokenCandidates.addAll(matches);
            }
        }
        if (tokenCandidates.size() == 1) {
            return tokenCandidates.iterator().next().formatForDisplay();
        }

        return "";
    }

    private static int scoreMatch(NormalizedTeacherName source, NormalizedTeacherName candidate) {
        if (source.tokens().isEmpty() || candidate.tokens().isEmpty()) {
            return 0;
        }

        Set<String> sourceTokens = new HashSet<>(source.tokens());
        int overlap = 0;
        for (String token : candidate.tokens()) {
            if (sourceTokens.contains(token)) {
                overlap++;
            }
        }
        if (overlap == 0) {
            return 0;
        }

        int score = overlap * 2;
        String sourcePrimary = source.tokens().get(0);
        String candidatePrimary = candidate.tokens().get(0);
        if (sourcePrimary.equals(candidatePrimary)) {
            score += 2;
        } else if (sourcePrimary.length() == 1 && candidatePrimary.startsWith(sourcePrimary)) {
            score += 1;
        }

        if (source.tokens().size() == candidate.tokens().size() && overlap == source.tokens().size()) {
            score += 2;
        }
        return score;
    }

    private static TableLayout detectLayout(List<Word> words) {
        if (words.isEmpty()) {
            return null;
        }

        int headerBucket = findHeaderBucket(words);
        if (headerBucket < 0) {
            return null;
        }

        List<Word> headerWords = words.stream()
                .filter(word -> bucketY(word.y()) == headerBucket)
                .toList();

        float employeeCenterX = findCenterXForToken(headerWords, "ZAMESTNANEC");
        List<DayHeader> dayHeaders = findDayHeaders(headerWords);
        if (employeeCenterX < 0 || dayHeaders.size() < 5) {
            return null;
        }
        dayHeaders.sort(Comparator.comparingDouble(DayHeader::centerX));

        float nameColumnRight = midpoint(employeeCenterX, dayHeaders.get(0).centerX());
        List<ColumnRange> dayColumns = new ArrayList<>();
        for (int i = 0; i < dayHeaders.size(); i++) {
            float left = i == 0 ? nameColumnRight : midpoint(dayHeaders.get(i - 1).centerX(), dayHeaders.get(i).centerX());
            float right = i == dayHeaders.size() - 1
                    ? Float.MAX_VALUE
                    : midpoint(dayHeaders.get(i).centerX(), dayHeaders.get(i + 1).centerX());
            dayColumns.add(new ColumnRange(dayHeaders.get(i).dayLabel(), left, right));
        }
        return new TableLayout(nameColumnRight, List.copyOf(dayColumns), headerBucket);
    }

    private static List<ConsultationHours> parsePageRows(List<Word> words, TableLayout layout, boolean skipHeader) {
        TreeMap<Integer, List<Word>> rowsByY = new TreeMap<>();
        for (Word word : words) {
            if (skipHeader && bucketY(word.y()) <= layout.headerBucket()) {
                continue;
            }
            rowsByY.computeIfAbsent(bucketY(word.y()), key -> new ArrayList<>()).add(word);
        }

        List<RowLine> lines = new ArrayList<>();
        for (List<Word> rowWords : rowsByY.values()) {
            rowWords.sort(Comparator.comparingDouble(Word::xStart));
            lines.add(toRowLine(rowWords, layout.nameColumnRight(), layout.dayColumns()));
        }

        List<ConsultationHours> result = new ArrayList<>();
        MutableTeacherRow current = null;
        for (RowLine line : lines) {
            String rawName = normalizeWhitespace(line.nameText());
            boolean hasDayData = line.hasDayData();

            if (looksLikeTeacherName(rawName)) {
                if (current != null && !current.scheduleByDay.isEmpty()) {
                    result.add(current.toConsultationHours());
                }
                current = new MutableTeacherRow(rawName);
                current.absorb(line.dayValues());
                continue;
            }

            if (looksLikeLegend(rawName)) {
                continue;
            }

            if (current != null && hasDayData) {
                current.absorb(line.dayValues());
            }
        }

        if (current != null && !current.scheduleByDay.isEmpty()) {
            result.add(current.toConsultationHours());
        }

        return result;
    }

    private static RowLine toRowLine(List<Word> words, float nameColumnRight, List<ColumnRange> dayColumns) {
        List<String> nameTokens = new ArrayList<>();
        Map<String, StringBuilder> dayBuilders = new LinkedHashMap<>();
        for (String day : DAY_ORDER) {
            dayBuilders.put(day, new StringBuilder());
        }

        for (Word word : words) {
            if (word.xCenter() < nameColumnRight) {
                nameTokens.add(word.text());
                continue;
            }

            for (ColumnRange columnRange : dayColumns) {
                if (word.xCenter() >= columnRange.leftX() && word.xCenter() < columnRange.rightX()) {
                    StringBuilder builder = dayBuilders.get(columnRange.dayLabel());
                    if (builder.length() > 0) {
                        builder.append(' ');
                    }
                    builder.append(word.text());
                    break;
                }
            }
        }

        Map<String, String> dayValues = new LinkedHashMap<>();
        for (String day : DAY_ORDER) {
            String value = parseDayCell(dayBuilders.get(day).toString());
            dayValues.put(day, value);
        }

        return new RowLine(String.join(" ", nameTokens), dayValues);
    }

    private static String parseDayCell(String rawValue) {
        String normalized = normalizeWhitespace(rawValue);
        if (normalized.isBlank()) {
            return "";
        }

        Matcher timeMatcher = TIME_PATTERN.matcher(normalized);
        if (timeMatcher.find()) {
            return timeMatcher.group(1) + "-" + timeMatcher.group(2);
        }

        if (normalized.toLowerCase(Locale.ROOT).contains("x")) {
            return "x";
        }
        return "";
    }

    private static boolean looksLikeTeacherName(String rawText) {
        String normalized = normalizeWhitespace(rawText);
        if (normalized.isBlank()) {
            return false;
        }
        if (looksLikeRoleLine(normalized) || looksLikeLegend(normalized)) {
            return false;
        }
        NormalizedTeacherName name = normalizeTeacherName(normalized);
        return name.tokens().size() >= 2;
    }

    private static boolean looksLikeRoleLine(String text) {
        String normalized = normalizePlain(text);
        for (String token : ROLE_HINT_TOKENS) {
            if (normalized.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeLegend(String text) {
        String normalized = normalizePlain(text);
        return normalized.contains("v dany den vyucujici neni pritomen");
    }

    private static List<Word> extractWordsForPage(PDDocument document, int page) throws IOException {
        WordCollectorStripper stripper = new WordCollectorStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        stripper.getText(document);
        return stripper.words();
    }

    private static int findHeaderBucket(List<Word> words) {
        Map<Integer, Set<String>> tokensByBucket = new HashMap<>();
        for (Word word : words) {
            int bucket = bucketY(word.y());
            tokensByBucket.computeIfAbsent(bucket, key -> new HashSet<>()).add(normalizeHeaderToken(word.text()));
        }

        int bestBucket = -1;
        int bestScore = 0;
        for (Map.Entry<Integer, Set<String>> entry : tokensByBucket.entrySet()) {
            Set<String> tokens = entry.getValue();
            int score = 0;
            if (tokens.contains("PO")) {
                score++;
            }
            if (tokens.contains("UT")) {
                score++;
            }
            if (tokens.contains("ST")) {
                score++;
            }
            if (tokens.contains("CT")) {
                score++;
            }
            if (tokens.contains("PA")) {
                score++;
            }
            if (tokens.stream().anyMatch(token -> token.startsWith("ZAMESTNANEC"))) {
                score++;
            }
            if (score > bestScore) {
                bestScore = score;
                bestBucket = entry.getKey();
            }
        }
        return bestScore >= 5 ? bestBucket : -1;
    }

    private static List<DayHeader> findDayHeaders(List<Word> words) {
        List<DayHeader> result = new ArrayList<>();
        for (Word word : words) {
            String token = normalizeHeaderToken(word.text());
            String label = switch (token) {
                case "PO" -> "PO";
                case "UT" -> "ÚT";
                case "ST" -> "ST";
                case "CT" -> "ČT";
                case "PA" -> "PÁ";
                default -> "";
            };
            if (!label.isBlank()) {
                result.add(new DayHeader(label, word.xCenter()));
            }
        }
        return result;
    }

    private static float findCenterXForToken(List<Word> words, String tokenPrefix) {
        for (Word word : words) {
            if (normalizeHeaderToken(word.text()).startsWith(tokenPrefix)) {
                return word.xCenter();
            }
        }
        return -1f;
    }

    private static int bucketY(float y) {
        return Math.round(y / 2f);
    }

    private static float midpoint(float left, float right) {
        return (left + right) / 2f;
    }

    private static String normalizeHeaderToken(String value) {
        return normalizePlain(value).replaceAll("[^A-Z]", "");
    }

    private static String normalizePlain(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
        return normalized.replace('\u00A0', ' ').trim();
    }

    private static String normalizeWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizedNameKey(String teacherName) {
        return normalizeTeacherName(teacherName).joinedTokens();
    }

    private static NormalizedTeacherName normalizeTeacherName(String name) {
        String normalized = Normalizer.normalize(Objects.requireNonNullElse(name, ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace('\u00A0', ' ')
                .replaceAll("[^a-z\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (normalized.isBlank()) {
            return new NormalizedTeacherName(List.of());
        }

        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split(" ")) {
            if (token.isBlank() || TITLE_TOKENS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return new NormalizedTeacherName(List.copyOf(tokens));
    }

    public record ConsultationHours(String teacherName, Map<String, String> scheduleByDay) {
        public String formatForDisplay() {
            List<String> parts = new ArrayList<>();
            for (String day : DAY_ORDER) {
                String value = scheduleByDay.getOrDefault(day, "");
                if (value.isBlank()) {
                    continue;
                }
                if ("x".equalsIgnoreCase(value)) {
                    parts.add(day + " není přítomen");
                } else {
                    parts.add(day + " " + value);
                }
            }
            return String.join(", ", parts);
        }
    }

    private record DayHeader(String dayLabel, float centerX) {
    }

    private record ColumnRange(String dayLabel, float leftX, float rightX) {
    }

    private record TableLayout(float nameColumnRight, List<ColumnRange> dayColumns, int headerBucket) {
    }

    private record RowLine(String nameText, Map<String, String> dayValues) {
        boolean hasDayData() {
            for (String day : DAY_ORDER) {
                if (!safeTrim(dayValues.get(day)).isBlank()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class MutableTeacherRow {
        private final String teacherName;
        private final Map<String, String> scheduleByDay = new LinkedHashMap<>();

        private MutableTeacherRow(String teacherName) {
            this.teacherName = teacherName;
        }

        private void absorb(Map<String, String> dayValues) {
            for (String day : DAY_ORDER) {
                String current = safeTrim(scheduleByDay.get(day));
                String incoming = safeTrim(dayValues.get(day));
                if (!incoming.isBlank() && current.isBlank()) {
                    scheduleByDay.put(day, incoming);
                }
            }
        }

        private ConsultationHours toConsultationHours() {
            return new ConsultationHours(teacherName, Map.copyOf(scheduleByDay));
        }
    }

    private static final class WordCollectorStripper extends PDFTextStripper {
        private final List<Word> words = new ArrayList<>();

        private WordCollectorStripper() throws IOException {
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            StringBuilder tokenBuilder = new StringBuilder();
            float xStart = 0;
            float xEnd = 0;
            float y = 0;
            boolean inToken = false;

            for (TextPosition position : textPositions) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }
                char ch = unicode.charAt(0);
                if (Character.isWhitespace(ch)) {
                    if (inToken) {
                        words.add(new Word(tokenBuilder.toString(), xStart, xEnd, y));
                        tokenBuilder.setLength(0);
                        inToken = false;
                    }
                    continue;
                }

                float charStart = position.getXDirAdj();
                float charEnd = charStart + position.getWidthDirAdj();
                if (!inToken) {
                    inToken = true;
                    xStart = charStart;
                    xEnd = charEnd;
                    y = position.getYDirAdj();
                } else {
                    xEnd = Math.max(xEnd, charEnd);
                    y = Math.min(y, position.getYDirAdj());
                }
                tokenBuilder.append(ch);
            }

            if (inToken) {
                words.add(new Word(tokenBuilder.toString(), xStart, xEnd, y));
            }
        }

        private List<Word> words() {
            return List.copyOf(words);
        }
    }

    private record Word(String text, float xStart, float xEnd, float y) {
        float xCenter() {
            return (xStart + xEnd) / 2f;
        }
    }

    private record NormalizedTeacherName(List<String> tokens) {
        private String joinedTokens() {
            return String.join(" ", tokens);
        }
    }
}
