package tn.sncft.trino.securite;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import tn.sncft.trino.iam.dto.UtilisateurDTO;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security view of an authenticated user, built from the (already
 * validated) JWT claims — never from the password hash, which never leaves
 * the iam module.
 */
public class DetailsUtilisateur implements UserDetails {

    private final UtilisateurDTO utilisateur;

    public DetailsUtilisateur(UtilisateurDTO utilisateur) {
        this.utilisateur = utilisateur;
    }

    public UtilisateurDTO getUtilisateur() {
        return utilisateur;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + utilisateur.role().name()));
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return utilisateur.email();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return utilisateur.actif();
    }
}
