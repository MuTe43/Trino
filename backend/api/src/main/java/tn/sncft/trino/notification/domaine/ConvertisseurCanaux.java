package tn.sncft.trino.notification.domaine;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code canaux} is one varchar holding a CSV of {@link CanalType}, on both
 * {@link Abonnement} and {@link RegleAlerte}, as the phase file specifies.
 *
 * <p>An {@code EnumSet} on the Java side rather than a {@code List}: the column
 * is a set of choices, so a duplicate is meaningless and the order carries no
 * information. Reading normalises both away, which also means a row hand-edited
 * to {@code 'EMAIL,EMAIL'} cannot make the dispatcher send twice.
 *
 * <p>An unknown token is dropped rather than thrown on. This is read on the
 * event path: one bad row must not take out every subscriber's notification,
 * and the check constraints make it an unreachable state anyway.
 */
@Converter
public class ConvertisseurCanaux implements AttributeConverter<Set<CanalType>, String> {

    @Override
    public String convertToDatabaseColumn(Set<CanalType> canaux) {
        if (canaux == null || canaux.isEmpty()) {
            return "";
        }
        // Declaration order, not insertion order, so the stored string for a
        // given set is always the same one.
        return EnumSet.copyOf(canaux).stream()
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }

    @Override
    public Set<CanalType> convertToEntityAttribute(String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return EnumSet.noneOf(CanalType.class);
        }
        return Arrays.stream(valeur.split(","))
                .map(String::trim)
                .filter(nom -> !nom.isEmpty())
                .map(ConvertisseurCanaux::depuisNom)
                .filter(canal -> canal != null)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(CanalType.class)));
    }

    private static CanalType depuisNom(String nom) {
        try {
            return CanalType.valueOf(nom);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
