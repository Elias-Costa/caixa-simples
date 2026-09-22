package br.com.caixasimples.cadastro.internal;

import br.com.caixasimples.cadastro.application.CatalogoInicialService;
import br.com.caixasimples.contas.PrimeiroAcessoDaConta;
import br.com.caixasimples.shared.TenantContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * O cadastro reage ao primeiro acesso sem que Contas conheça os seus casos de uso. O ouvinte é
 * síncrono de propósito: a cópia precisa terminar na transação que grava a marca na Conta, para o
 * primeiro login já ter o catálogo e uma falha desfazer as duas escritas.
 */
@Component
class PrimeiroAcessoDaContaListener {

    private final CatalogoInicialService catalogo;

    PrimeiroAcessoDaContaListener(CatalogoInicialService catalogo) {
        this.catalogo = catalogo;
    }

    @EventListener
    void aoPrimeiroAcesso(PrimeiroAcessoDaConta evento) {
        if (!TenantContext.exigirAtual().valor().equals(evento.contaId())) {
            throw new IllegalStateException("primeiro acesso publicado para outra conta");
        }
        catalogo.aplicarPara(evento.tipoNegocio());
    }
}
