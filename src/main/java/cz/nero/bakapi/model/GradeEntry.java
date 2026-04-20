package cz.nero.bakapi.model;

public record GradeEntry(
        String sourceId,
        String subject,
        String teacher,
        String markText,
        String caption,
        String note,
        String weight,
        String date,
        String planStatus
) {
}
