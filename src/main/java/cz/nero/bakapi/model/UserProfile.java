package cz.nero.bakapi.model;

public record UserProfile(
        String id,
        String baseUrl,
        String username,
        String passwordSaltBase64,
        String passwordHashBase64,
        String encryptionSaltBase64,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {
}
