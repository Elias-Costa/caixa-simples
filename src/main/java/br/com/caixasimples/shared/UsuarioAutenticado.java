package br.com.caixasimples.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Quem está operando: o usuário autenticado e o perfil dele, como o banco os conhece agora.
 *
 * <p>É o que {@link UsuarioContext} guarda. O {@code usuarioId} vem do token; o {@code perfil}
 * vem do banco a cada requisição, e não do claim, para que inativar alguém ou trocar o perfil
 * dele valha na requisição seguinte, e não só quando o token expirar.
 */
public record UsuarioAutenticado(UUID usuarioId, Perfil perfil) {

    public UsuarioAutenticado {
        Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");
        Objects.requireNonNull(perfil, "perfil nao pode ser nulo");
    }

    public boolean ehAdmin() {
        return perfil == Perfil.ADMIN;
    }
}
