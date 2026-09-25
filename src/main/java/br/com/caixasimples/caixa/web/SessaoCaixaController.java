package br.com.caixasimples.caixa.web;

import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.caixa.application.SessaoCaixaService.ResumoDeSessao;
import br.com.caixasimples.caixa.application.SessaoCaixaService.ExtratoDaSessao;
import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.UsuarioAutenticado;
import br.com.caixasimples.shared.UsuarioContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Entrada HTTP da SessaoCaixa; Conta e usuário sempre vêm do token autenticado. */
@RestController
@RequestMapping("/api/caixa/sessoes")
class SessaoCaixaController {

    private final SessaoCaixaService sessoes;

    SessaoCaixaController(SessaoCaixaService sessoes) {
        this.sessoes = sessoes;
    }

    @PostMapping
    ResponseEntity<Criada> abrir(@Valid @RequestBody PedidoDeAbertura pedido) {
        UUID id = sessoes.abrir(Money.de(pedido.valorAbertura()));
        return ResponseEntity.created(URI.create("/api/caixa/sessoes/" + id)).body(new Criada(id));
    }

    @GetMapping("/aberta")
    ResponseEntity<SessaoNaResposta> abertaDoOperadorAtual() {
        return sessoes.abertaDoOperadorAtual().map(SessaoNaResposta::de).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping
    List<SessaoNaResposta> historico(@RequestParam LocalDate dia,
            @RequestParam(required = false) UUID operadorId) {
        UsuarioAutenticado usuario = UsuarioContext.exigirAtual();
        UUID operador = operadorId == null && !usuario.ehAdmin() ? usuario.usuarioId() : operadorId;
        return sessoes.historicoDoDia(dia, operador).stream().map(SessaoNaResposta::de).toList();
    }

    @GetMapping("/{id}")
    SessaoNaResposta consultar(@PathVariable UUID id) {
        return SessaoNaResposta.de(sessoes.consultarExtrato(id));
    }

    @PostMapping("/{id}/sangrias")
    ResponseEntity<Void> sangrar(@PathVariable UUID id, @Valid @RequestBody PedidoDeMovimento pedido) {
        sessoes.registrarSangria(id, Money.de(pedido.valor()), pedido.motivo());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/suprimentos")
    ResponseEntity<Void> suprir(@PathVariable UUID id, @Valid @RequestBody PedidoDeMovimento pedido) {
        sessoes.registrarSuprimento(id, Money.de(pedido.valor()), pedido.motivo());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/fechamento")
    ResultadoFechamento fechar(@PathVariable UUID id, @Valid @RequestBody PedidoDeFechamento pedido) {
        Money diferenca = sessoes.fechar(id, Money.de(pedido.valorContado()));
        return new ResultadoFechamento(diferenca.valor());
    }

    record PedidoDeAbertura(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
            BigDecimal valorAbertura) {
    }

    record PedidoDeMovimento(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
            BigDecimal valor, @NotBlank String motivo) {
    }

    record PedidoDeFechamento(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
            BigDecimal valorContado) {
    }

    record Criada(UUID id) {
    }

    record ResultadoFechamento(BigDecimal diferenca) {
    }

    record SessaoNaResposta(UUID id, UUID usuarioId, BigDecimal valorAbertura,
            BigDecimal valorFechamentoEsperado, BigDecimal valorFechamentoContado,
            BigDecimal diferenca, Instant abertaEm, Instant fechadaEm, String status,
            long versao, List<MovimentoNaResposta> movimentos) {

        static SessaoNaResposta de(ResumoDeSessao resumo) {
            return new SessaoNaResposta(resumo.id(), resumo.usuarioId(),
                    resumo.valorAbertura().valor(), resumo.valorFechamentoEsperado().valor(),
                    valorOuNulo(resumo.valorFechamentoContado()),
                    valorOuNulo(resumo.diferenca()), resumo.abertaEm(), resumo.fechadaEm(),
                    resumo.status().name(), resumo.versao(), null);
        }

        static SessaoNaResposta de(ExtratoDaSessao extrato) {
            SessaoNaResposta base = de(extrato.sessao());
            return new SessaoNaResposta(base.id(), base.usuarioId(), base.valorAbertura(),
                    base.valorFechamentoEsperado(), base.valorFechamentoContado(),
                    base.diferenca(), base.abertaEm(), base.fechadaEm(), base.status(), base.versao(),
                    extrato.movimentos().stream().map(MovimentoNaResposta::de).toList());
        }

        private static BigDecimal valorOuNulo(Money valor) {
            return valor == null ? null : valor.valor();
        }
    }

    record MovimentoNaResposta(UUID id, String tipo, BigDecimal valor, String motivo,
            UUID vendaId, UUID recebimentoId, Instant criadoEm) {

        static MovimentoNaResposta de(MovimentoCaixa movimento) {
            return new MovimentoNaResposta(movimento.id(), movimento.tipo().name(),
                    movimento.valor().valor(), movimento.motivo(), movimento.vendaId(),
                    movimento.recebimentoId(),
                    movimento.criadoEm());
        }
    }
}
