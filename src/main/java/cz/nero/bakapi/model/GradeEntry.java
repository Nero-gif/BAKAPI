package cz.nero.bakapi.model;

public record GradeEntry(
        String subject,
        String teacher,
        String markText,
        String caption,
        String note,
        String weight,
        String date
) {
}
