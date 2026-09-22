package br.com.caixasimples.contas;

import java.util.UUID;

/**
 * O primeiro login de um administrador nesta Conta já validou o tipo de negócio para a oferta do
 * catálogo inicial (RF32). Publicado dentro da transação que marca a Conta; quem copia os itens
 * participa da mesma transação para o login seguinte nunca ver a marca sem o catálogo.
 */
public record PrimeiroAcessoDaConta(UUID contaId, String tipoNegocio) {
}
