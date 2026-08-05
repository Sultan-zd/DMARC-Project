package com.teknologiia.dmarc.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The single place a password is judged acceptable.
 *
 * <p>Used by self-service sign-up, by an administrator creating an account, and by
 * a user changing their own — one definition, so the rules cannot drift apart
 * between entry points.
 *
 * <p>Length is weighted over exotic characters on purpose: a long passphrase beats
 * a short string with a symbol bolted on. The character-class rule exists mainly to
 * stop the obvious "password1234" shape.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 200;

    /** Rejected outright, in any capitalisation — these lead every breach list. */
    private static final Set<String> BANNED = Set.of(
            "password", "passw0rd", "motdepasse", "azerty", "qwerty", "123456",
            "12345678", "123456789", "1234567890", "letmein", "welcome",
            "admin", "administrator", "changeme", "iloveyou", "dragon", "monkey",
            "abc123", "football", "baseball", "sunshine", "princess", "teknologiia"
    );

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#$%^&*-_=+?";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Redraws before giving up. Sixteen is far beyond what chance needs. */
    private static final int GENERATE_ATTEMPTS = 16;

    /**
     * @return every rule the candidate breaks, empty when acceptable
     */
    public List<String> check(String password, String username, String email) {
        List<String> problems = new ArrayList<>();

        if (password == null || password.isBlank()) {
            return List.of("Enter a password.");
        }
        if (password.length() < MIN_LENGTH) {
            problems.add("Use at least " + MIN_LENGTH + " characters.");
        }
        if (password.length() > MAX_LENGTH) {
            problems.add("Use at most " + MAX_LENGTH + " characters.");
        }

        int classes = 0;
        if (password.chars().anyMatch(Character::isUpperCase)) classes++;
        if (password.chars().anyMatch(Character::isLowerCase)) classes++;
        if (password.chars().anyMatch(Character::isDigit)) classes++;
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) classes++;
        if (classes < 3) {
            problems.add("Mix at least three of: uppercase, lowercase, digits, symbols.");
        }

        String lower = password.toLowerCase(Locale.ROOT);
        if (BANNED.stream().anyMatch(lower::contains)) {
            problems.add("This contains a commonly guessed word. Choose something less predictable.");
        }

        // A password built from the account's own name is the first thing tried.
        if (username != null && username.length() >= 3 && lower.contains(username.toLowerCase(Locale.ROOT))) {
            problems.add("Do not include the username.");
        }
        if (email != null && email.contains("@")) {
            String local = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
            if (local.length() >= 3 && lower.contains(local)) {
                problems.add("Do not include your email address.");
            }
        }

        if (hasLongRun(lower)) {
            problems.add("Avoid long runs of repeated or sequential characters.");
        }

        return problems;
    }

    /** Throws a 400 carrying the first failed rule; does nothing when acceptable. */
    public void enforce(String password, String username, String email) {
        List<String> problems = check(password, username, email);
        if (!problems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problems.get(0));
        }
    }

    /**
     * Builds a strong password.
     *
     * <p>Ambiguous glyphs (O/0, l/1/I) are excluded from the alphabets so the result
     * survives being read aloud or copied by hand.
     */
    public String generate() {
        // Drawing at random can land on four sequential characters by chance — about
        // once in a few hundred — and the rules would then reject a password this
        // class had just issued. Re-drawing is the fix: the generator must not be
        // able to emit something the validator refuses.
        for (int attempt = 0; attempt < GENERATE_ATTEMPTS; attempt++) {
            String candidate = draw();
            // Lowercased, exactly as check() tests it: "aBcD" is a run to the
            // validator and would not be one to a case-sensitive check here.
            if (!hasLongRun(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        // Unreachable in practice; failing loudly beats returning a rejected password.
        throw new IllegalStateException("Could not generate a password satisfying the policy");
    }

    private String draw() {
        String all = UPPER + LOWER + DIGITS + SYMBOLS;
        StringBuilder out = new StringBuilder();

        // Guarantee one of each class, then fill to length.
        out.append(pick(UPPER)).append(pick(LOWER)).append(pick(DIGITS)).append(pick(SYMBOLS));
        while (out.length() < 16) {
            out.append(pick(all));
        }

        // Shuffle so the guaranteed characters are not always in the same positions.
        List<Character> chars = new ArrayList<>();
        for (char c : out.toString().toCharArray()) {
            chars.add(c);
        }
        java.util.Collections.shuffle(chars, RANDOM);

        StringBuilder shuffled = new StringBuilder();
        chars.forEach(shuffled::append);
        return shuffled.toString();
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
    }

    /** Four or more identical, ascending or descending characters in a row. */
    private static boolean hasLongRun(String value) {
        int repeat = 1;
        int ascending = 1;
        int descending = 1;

        for (int i = 1; i < value.length(); i++) {
            char previous = value.charAt(i - 1);
            char current = value.charAt(i);

            repeat = current == previous ? repeat + 1 : 1;
            ascending = current == previous + 1 ? ascending + 1 : 1;
            descending = current == previous - 1 ? descending + 1 : 1;

            if (repeat >= 4 || ascending >= 4 || descending >= 4) {
                return true;
            }
        }
        return false;
    }
}
