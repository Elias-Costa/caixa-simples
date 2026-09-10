package br.com.caixasimples.contas.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dados da conta criada pelo {@link SeedDeConta}, vindos do ambiente.
 *
 * <p>Sem valor padrão para {@code email} e {@code senha}: uma senha padrão em código seria a mesma
 * em toda instalação.
 *
 * @param tipoNegocio opcional; casa com {@code modelo_produto.tipo_negocio} para sugerir o
 *                    catálogo inicial (RF32), e nulo significa cadastro começando em branco
 */
@ConfigurationProperties(prefix = "caixa-simples.seed")
record PropriedadesDoSeed(
        String nomeNegocio,
        String tipoNegocio,
        String nomeUsuario,
        String email,
        String senha) {

    PropriedadesDoSeed {
        nomeUsuario = (nomeUsuario == null || nomeUsuario.isBlank()) ? "Administrador" : nomeUsuario;
    }
}
