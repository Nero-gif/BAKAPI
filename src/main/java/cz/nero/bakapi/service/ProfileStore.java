package cz.nero.bakapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.nero.bakapi.model.GradeEntry;
import cz.nero.bakapi.model.UserProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class ProfileStore {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final TypeReference<List<UserProfile>> PROFILE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<GradeEntry>> GRADE_LIST_TYPE = new TypeReference<>() {
    };

    private static final int HASH_ITERATIONS = 180_000;
    private static final int ENCRYPTION_ITERATIONS = 240_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final Path appDir;
    private final Path profilesFile;
    private final Path cacheDir;

    public ProfileStore() {
        this.appDir = Path.of(System.getProperty("user.home"), ".bakapi");
        this.profilesFile = appDir.resolve("profiles.json");
        this.cacheDir = appDir.resolve("cache");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new IllegalStateException("Nepodařilo se vytvořit lokální úložiště profilů.", e);
        }
    }

    public List<UserProfile> loadProfiles() throws IOException {
        if (!Files.exists(profilesFile)) {
            return List.of();
        }

        List<UserProfile> profiles = OBJECT_MAPPER.readValue(profilesFile.toFile(), PROFILE_LIST_TYPE);
        if (profiles == null) {
            return List.of();
        }
        return List.copyOf(profiles);
    }

    public Optional<UserProfile> findProfile(String baseUrl, String username) throws IOException {
        String baseKey = normalizeBaseUrl(baseUrl);
        String userKey = normalizeUsername(username);
        return loadProfiles().stream()
                .filter(profile -> normalizeBaseUrl(profile.baseUrl()).equals(baseKey)
                        && normalizeUsername(profile.username()).equals(userKey))
                .findFirst();
    }

    public UserProfile saveOrUpdateProfile(String baseUrl, String username, char[] password)
            throws IOException, GeneralSecurityException {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        String usernameValue = requireText(username, "Uživatelské jméno nesmí být prázdné.");
        String normalizedUsername = normalizeUsername(usernameValue);
        ensurePassword(password);

        long now = Instant.now().toEpochMilli();
        List<UserProfile> profiles = new ArrayList<>(loadProfiles());
        int existingIndex = findProfileIndex(profiles, normalizedBaseUrl, normalizedUsername);

        UserProfile updatedProfile;
        if (existingIndex >= 0) {
            UserProfile existing = profiles.get(existingIndex);
            if (isPasswordValid(existing, password)) {
                updatedProfile = new UserProfile(
                        existing.id(),
                        normalizedBaseUrl,
                        usernameValue,
                        existing.passwordSaltBase64(),
                        existing.passwordHashBase64(),
                        existing.encryptionSaltBase64(),
                        existing.createdAtEpochMillis(),
                        now
                );
            } else {
                byte[] passwordSalt = randomBytes(SALT_BYTES);
                byte[] encryptionSalt = randomBytes(SALT_BYTES);
                byte[] passwordHash = deriveKey(password, passwordSalt, HASH_ITERATIONS);
                updatedProfile = new UserProfile(
                        existing.id(),
                        normalizedBaseUrl,
                        usernameValue,
                        encode(passwordSalt),
                        encode(passwordHash),
                        encode(encryptionSalt),
                        existing.createdAtEpochMillis(),
                        now
                );
            }
            profiles.set(existingIndex, updatedProfile);
        } else {
            byte[] passwordSalt = randomBytes(SALT_BYTES);
            byte[] encryptionSalt = randomBytes(SALT_BYTES);
            byte[] passwordHash = deriveKey(password, passwordSalt, HASH_ITERATIONS);
            updatedProfile = new UserProfile(
                    UUID.randomUUID().toString(),
                    normalizedBaseUrl,
                    usernameValue,
                    encode(passwordSalt),
                    encode(passwordHash),
                    encode(encryptionSalt),
                    now,
                    now
            );
            profiles.add(updatedProfile);
        }

        saveProfiles(profiles);
        return updatedProfile;
    }

    public List<GradeEntry> saveCachedGrades(UserProfile profile, char[] password, List<GradeEntry> grades)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(profile, "profile");
        ensurePassword(password);
        if (!isPasswordValid(profile, password)) {
            throw new GeneralSecurityException("Neplatné heslo pro vybraný profil.");
        }

        byte[] key = deriveKey(password, decode(profile.encryptionSaltBase64()), ENCRYPTION_ITERATIONS);
        Path cacheFile = cacheDir.resolve(profile.id() + ".grades.enc");
        List<GradeEntry> mergedGrades = mergeWithExistingGrades(cacheFile, key, grades);
        byte[] iv = randomBytes(GCM_IV_BYTES);
        byte[] plainBytes = OBJECT_MAPPER.writeValueAsBytes(mergedGrades);
        byte[] cipherBytes = encrypt(plainBytes, key, iv);

        EncryptedPayload payload = new EncryptedPayload(1, encode(iv), encode(cipherBytes));
        writeJsonAtomically(cacheFile, payload);
        return mergedGrades;
    }

    public List<GradeEntry> loadCachedGrades(UserProfile profile, char[] password)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(profile, "profile");
        ensurePassword(password);
        if (!isPasswordValid(profile, password)) {
            throw new GeneralSecurityException("Heslo k účtu není platné.");
        }

        Path cacheFile = cacheDir.resolve(profile.id() + ".grades.enc");
        if (!Files.exists(cacheFile)) {
            throw new IOException("Pro vybraný profil nejsou uložené offline známky.");
        }

        EncryptedPayload payload = OBJECT_MAPPER.readValue(cacheFile.toFile(), EncryptedPayload.class);
        if (payload == null || payload.iv() == null || payload.ciphertext() == null) {
            throw new IOException("Poškozený soubor offline známek.");
        }

        byte[] key = deriveKey(password, decode(profile.encryptionSaltBase64()), ENCRYPTION_ITERATIONS);
        try {
            return decryptGrades(payload, key);
        } catch (GeneralSecurityException e) {
            throw new GeneralSecurityException("Offline známky nelze odemknout. Zkontroluj heslo.", e);
        }
    }

    private boolean isPasswordValid(UserProfile profile, char[] password) throws GeneralSecurityException {
        byte[] expected = decode(profile.passwordHashBase64());
        byte[] actual = deriveKey(password, decode(profile.passwordSaltBase64()), HASH_ITERATIONS);
        return MessageDigest.isEqual(expected, actual);
    }

    private void saveProfiles(List<UserProfile> profiles) throws IOException {
        writeJsonAtomically(profilesFile, profiles);
    }

    private static int findProfileIndex(List<UserProfile> profiles, String normalizedBaseUrl, String normalizedUsername) {
        for (int i = 0; i < profiles.size(); i++) {
            UserProfile profile = profiles.get(i);
            if (normalizeBaseUrl(profile.baseUrl()).equals(normalizedBaseUrl)
                    && normalizeUsername(profile.username()).equals(normalizedUsername)) {
                return i;
            }
        }
        return -1;
    }

    private static void writeJsonAtomically(Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
        Path tempFile = Files.createTempFile(target.getParent(), "bakapi-", ".tmp");
        try {
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), value);
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static byte[] deriveKey(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec keySpec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        return factory.generateSecret(keySpec).getEncoded();
    }

    private static byte[] encrypt(byte[] plainBytes, byte[] key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(plainBytes);
    }

    private static byte[] decrypt(byte[] cipherBytes, byte[] key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(cipherBytes);
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String value = requireText(baseUrl, "URL adresa nesmí být prázdná.");
        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeUsername(String username) {
        return requireText(username, "Uživatelské jméno nesmí být prázdné.").toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static void ensurePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Heslo nesmí být prázdné.");
        }
    }

    private List<GradeEntry> mergeWithExistingGrades(Path cacheFile, byte[] key, List<GradeEntry> incomingGrades)
            throws IOException, GeneralSecurityException {
        if (!Files.exists(cacheFile)) {
            return List.copyOf(incomingGrades);
        }

        EncryptedPayload existingPayload = OBJECT_MAPPER.readValue(cacheFile.toFile(), EncryptedPayload.class);
        if (existingPayload == null || existingPayload.iv() == null || existingPayload.ciphertext() == null) {
            return List.copyOf(incomingGrades);
        }

        try {
            List<GradeEntry> existingGrades = decryptGrades(existingPayload, key);
            return mergeGrades(existingGrades, incomingGrades);
        } catch (GeneralSecurityException e) {
            return List.copyOf(incomingGrades);
        }
    }

    private static List<GradeEntry> decryptGrades(EncryptedPayload payload, byte[] key)
            throws GeneralSecurityException, IOException {
        byte[] plainBytes = decrypt(decode(payload.ciphertext()), key, decode(payload.iv()));
        List<GradeEntry> grades = OBJECT_MAPPER.readValue(plainBytes, GRADE_LIST_TYPE);
        return grades == null ? List.of() : grades;
    }

    private static List<GradeEntry> mergeGrades(List<GradeEntry> existing, List<GradeEntry> incoming) {
        if (existing.isEmpty()) {
            return List.copyOf(incoming);
        }

        HashMap<String, GradeEntry> existingByIdentity = new HashMap<>();
        HashMap<String, Integer> existingSequence = new HashMap<>();
        for (GradeEntry grade : existing) {
            String key = gradeIdentityKey(grade, existingSequence);
            existingByIdentity.put(key, grade);
        }

        List<GradeEntry> merged = new ArrayList<>();
        HashMap<String, Integer> incomingSequence = new HashMap<>();
        for (GradeEntry grade : incoming) {
            String key = gradeIdentityKey(grade, incomingSequence);
            GradeEntry existingGrade = existingByIdentity.get(key);
            if (existingGrade != null) {
                GradeEntry mergedGrade = mergeGradeFields(existingGrade, grade);
                if (gradesEqualIncludingPlan(existingGrade, mergedGrade)) {
                    merged.add(existingGrade);
                } else {
                    merged.add(mergedGrade);
                }
            } else {
                merged.add(grade);
            }
        }

        return List.copyOf(merged);
    }

    private static String gradeIdentityKey(GradeEntry grade, HashMap<String, Integer> sequenceMap) {
        String sourceId = safeText(grade.sourceId());
        String baseKey;
        if (!sourceId.isBlank()) {
            baseKey = "id:" + sourceId;
        } else {
            baseKey = "f:"
                    + safeText(grade.subject()).toLowerCase(Locale.ROOT) + "|"
                    + safeText(grade.markText()).toLowerCase(Locale.ROOT) + "|"
                    + safeText(grade.weight()).toLowerCase(Locale.ROOT) + "|"
                    + safeText(grade.caption()).toLowerCase(Locale.ROOT) + "|"
                    + safeText(grade.date()).toLowerCase(Locale.ROOT);
        }

        int sequence = sequenceMap.merge(baseKey, 1, Integer::sum);
        return baseKey + "#" + sequence;
    }

    private static GradeEntry mergeGradeFields(GradeEntry existing, GradeEntry incoming) {
        String mergedPlanStatus = safeText(incoming.planStatus());
        if (mergedPlanStatus.isBlank()) {
            mergedPlanStatus = safeText(existing.planStatus());
        }

        return new GradeEntry(
                incoming.sourceId(),
                incoming.subject(),
                incoming.teacher(),
                incoming.markText(),
                incoming.caption(),
                incoming.note(),
                incoming.weight(),
                incoming.date(),
                mergedPlanStatus
        );
    }

    private static boolean gradesEqualIncludingPlan(GradeEntry left, GradeEntry right) {
        return Objects.equals(left.sourceId(), right.sourceId())
                && Objects.equals(left.subject(), right.subject())
                && Objects.equals(left.teacher(), right.teacher())
                && Objects.equals(left.markText(), right.markText())
                && Objects.equals(left.caption(), right.caption())
                && Objects.equals(left.note(), right.note())
                && Objects.equals(left.weight(), right.weight())
                && Objects.equals(left.date(), right.date())
                && Objects.equals(safeText(left.planStatus()), safeText(right.planStatus()));
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private record EncryptedPayload(int version, String iv, String ciphertext) {
    }
}
