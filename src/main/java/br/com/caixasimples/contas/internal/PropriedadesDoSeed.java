package br.com.caixasimples.contas.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dados da conta criada pelo {@link SeedDeConta}, vindos da linha de comando ou do ambiente.
 *
 * <p>Sem valor padrao para {@code email} e {@code senha}: uma senha padrao em codigo seria a mesma
 * em toda instalacao.
 *
 * @param tipoNegocio opcional — casa com {@code modelo_produto.tipo_negocio} para sugerir o
 *                    catalogo inicial (RF32); nulo significa cadastro comecando em branco
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
