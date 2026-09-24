package br.com.caixasimples.vendas.web;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.application.Comprovante;
import br.com.caixasimples.vendas.application.VendaService.ComprovanteDeRecebimento;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.application.VendaPixService;
import br.com.caixasimples.vendas.application.VendaService.VendaParaTela;
import br.com.caixasimples.vendas.StatusVenda;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Entrada HTTP do PDV: Conta e operador vêm do contexto autenticado, e preço vem do cadastro. */
@RestController
@RequestMapping("/api/vendas")
class VendaController {

    private final VendaService vendas;
    private final VendaPixService pix;

    VendaController(VendaService vendas, VendaPixService pix) {
        this.vendas = vendas;
        this.pix = pix;
    }

    @PostMapping
    ResponseEntity<Criada> iniciar(@Valid @RequestBody PedidoDeInicio pedido) {
        UUID id = vendas.iniciar(pedido.sessaoCaixaId());
        return ResponseEntity.created(URI.create("/api/vendas/" + id)).body(new Criada(id));
    }

    @GetMapping
    List<ResumoNaResposta> listar(@RequestParam UUID sessaoCaixaId) {
        return vendas.vendasDaSessao(sessaoCaixaId).stream().map(ResumoNaResposta::de).toList();
    }

    @GetMapping("/{id}")
    VendaNaResposta consultar(@PathVariable UUID id) {
        return VendaNaResposta.de(vendas.consultar(id));
    }

    @PostMapping("/{id}/itens")
    ResponseEntity<Criada> adicionarItem(@PathVariable UUID id,
            @Valid @RequestBody PedidoDeItem pedido) {
        UUID itemId = vendas.adicionarItem(id, pedido.produtoId(), pedido.quantidade(),
                Money.de(pedido.desconto()));
        return ResponseEntity.created(URI.create("/api/vendas/" + id + "/itens/" + itemId))
                .body(new Criada(itemId));
    }

    @DeleteMapping("/{id}/itens/{itemId}")
    ResponseEntity<Void> removerItem(@PathVariable UUID id, @PathVariable UUID itemId) {
        vendas.removerItem(id, itemId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/desconto")
    ResponseEntity<Void> aplicarDesconto(@PathVariable UUID id,
            @Valid @RequestBody PedidoDeDesconto pedido) {
        vendas.aplicarDesconto(id, Money.de(pedido.valor()));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/cliente")
    ResponseEntity<Void> vincularCliente(@PathVariable UUID id,
            @Valid @RequestBody PedidoDeCliente pedido) {
        vendas.vincularCliente(id, pedido.clienteId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pagamentos")
    Troco registrarPagamento(@PathVariable UUID id, @Valid @RequestBody PedidoDePagamento pedido) {
        Money recebido = pedido.valorRecebido() == null ? null : Money.de(pedido.valorRecebido());
        Money troco = vendas.registrarPagamento(id, new SolicitacaoPagamento(pedido.forma(),
                Money.de(pedido.valor()), recebido));
        return new Troco(troco.valor());
    }

    @PostMapping("/{id}/pagamentos/pix")
    ParcelaNaResposta cobrarPix(@PathVariable UUID id,
            @Valid @RequestBody PedidoDePix pedido) {
        return ParcelaNaResposta.de(pix.cobrar(id, pedido.tentativaId(), Money.de(pedido.valor())));
    }

    @PostMapping("/{id}/conclusao")
    ResponseEntity<Void> concluir(@PathVariable UUID id) {
        vendas.concluir(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancelamento")
    ResponseEntity<Void> cancelar(@PathVariable UUID id) {
        vendas.cancelar(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/recebimentos")
    ResponseEntity<RecebimentoRegistradoNaResposta> receber(@PathVariable UUID id,
            @Valid @RequestBody PedidoDeRecebimento pedido) {
        var registrado = vendas.receber(id, Money.de(pedido.valor()), pedido.forma());
        return ResponseEntity.created(URI.create("/api/vendas/" + id + "/recebimentos/"
                + registrado.id() + "/comprovante"))
                .body(new RecebimentoRegistradoNaResposta(registrado.id(),
                        registrado.saldoDevedor().valor()));
    }

    @GetMapping("/{id}/recebimentos/{recebimentoId}/comprovante")
    ComprovanteDeRecebimentoNaResposta comprovanteDeRecebimento(@PathVariable UUID id,
            @PathVariable UUID recebimentoId) {
        return ComprovanteDeRecebimentoNaResposta.de(
                vendas.comprovanteDeRecebimento(id, recebimentoId));
    }

    @GetMapping("/{id}/comprovante")
    ComprovanteNaResposta comprovante(@PathVariable UUID id) {
        return ComprovanteNaResposta.de(vendas.comprovante(id));
    }

    record PedidoDeInicio(@NotNull UUID sessaoCaixaId) {
    }

    record PedidoDeItem(@NotNull UUID produtoId,
            @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3)
            BigDecimal quantidade,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
            BigDecimal desconto) {
    }

    record PedidoDeDesconto(@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
            BigDecimal valor) {
    }

    record PedidoDeCliente(@NotNull UUID clienteId) {
    }

    record PedidoDeRecebimento(@NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2)
            BigDecimal valor, @NotNull FormaPagamento forma) {
    }

    record PedidoDePagamento(@NotNull FormaPagamento forma,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2)
            BigDecimal valor,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
            BigDecimal valorRecebido) {
    }

    record PedidoDePix(@NotNull UUID tentativaId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2)
            BigDecimal valor) {
    }

    record Criada(UUID id) {
    }

    record Troco(BigDecimal troco) {
    }

    record RecebimentoRegistradoNaResposta(UUID id, BigDecimal saldoDevedor) {
    }

    record ComprovanteDeRecebimentoNaResposta(UUID vendaId, UUID recebimentoId, UUID clienteId,
            String nomeCliente, UUID sessaoCaixaId, Instant recebidoEm, FormaPagamento forma,
            BigDecimal valor, BigDecimal saldoApos) {
        static ComprovanteDeRecebimentoNaResposta de(ComprovanteDeRecebimento comprovante) {
            return new ComprovanteDeRecebimentoNaResposta(comprovante.vendaId(),
                    comprovante.recebimentoId(), comprovante.clienteId(),
                    comprovante.nomeCliente(), comprovante.sessaoCaixaId(),
                    comprovante.recebidoEm(), comprovante.forma(), comprovante.valor().valor(),
                    comprovante.saldoApos().valor());
        }
    }

    record ResumoNaResposta(UUID id, UUID sessaoCaixaId, UUID usuarioId, StatusVenda status,
            BigDecimal total, Instant criadoEm) {
        static ResumoNaResposta de(VendaService.ResumoDaVenda resumo) {
            return new ResumoNaResposta(resumo.id(), resumo.sessaoCaixaId(), resumo.usuarioId(),
                    resumo.status(), resumo.total().valor(), resumo.criadoEm());
        }
    }

    record VendaNaResposta(UUID id, UUID sessaoCaixaId, UUID usuarioId, UUID clienteId,
            BigDecimal saldoDevedor, StatusVenda status,
            Instant criadoEm, BigDecimal descontoDaVenda, BigDecimal total, BigDecimal pago,
            BigDecimal faltaPagar, List<ItemNaResposta> itens, List<ParcelaNaResposta> parcelas,
            List<RecebimentoNaResposta> recebimentos) {
        static VendaNaResposta de(VendaParaTela venda) {
            return new VendaNaResposta(venda.id(), venda.sessaoCaixaId(), venda.usuarioId(),
                    venda.clienteId(), venda.saldoDevedor().valor(),
                    venda.status(), venda.criadoEm(), venda.descontoDaVenda().valor(),
                    venda.total().valor(), venda.pago().valor(), venda.faltaPagar().valor(),
                    venda.itens().stream().map(ItemNaResposta::de).toList(),
                    venda.parcelas().stream().map(ParcelaNaResposta::de).toList(),
                    venda.recebimentos().stream().map(RecebimentoNaResposta::de).toList());
        }
    }

    record RecebimentoNaResposta(UUID id, UUID sessaoCaixaId, BigDecimal valor,
            FormaPagamento forma, Instant criadoEm) {
        static RecebimentoNaResposta de(VendaService.RecebimentoParaTela r) {
            return new RecebimentoNaResposta(r.id(), r.sessaoCaixaId(), r.valor().valor(),
                    r.forma(), r.criadoEm());
        }
    }

    record ItemNaResposta(UUID id, UUID produtoId, String nome, BigDecimal quantidade,
            BigDecimal precoUnitario, BigDecimal desconto, BigDecimal subtotal) {
        static ItemNaResposta de(VendaService.ItemParaTela item) {
            return new ItemNaResposta(item.id(), item.produtoId(), item.nome(), item.quantidade(),
                    item.precoUnitario().valor(), item.desconto().valor(), item.subtotal().valor());
        }
    }

    record ParcelaNaResposta(UUID id, FormaPagamento forma, BigDecimal valor,
            StatusPagamento status, BigDecimal troco, PixNaResposta pix) {
        static ParcelaNaResposta de(VendaService.ParcelaParaTela parcela) {
            return new ParcelaNaResposta(parcela.id(), parcela.forma(), parcela.valor().valor(),
                    parcela.status(), parcela.troco().valor(), PixNaResposta.de(parcela.cobrancaPix()));
        }

        static ParcelaNaResposta de(br.com.caixasimples.vendas.domain.Pagamento parcela) {
            return new ParcelaNaResposta(parcela.id(), parcela.forma(), parcela.valor().valor(),
                    parcela.status(), parcela.troco().valor(), PixNaResposta.de(parcela.cobrancaPix()));
        }
    }

    record PixNaResposta(String txid, Instant expiraEm, String copiaECola,
            CobrancaPix.Estado estado) {
        static PixNaResposta de(CobrancaPix cobranca) {
            return cobranca == null ? null : new PixNaResposta(cobranca.txid(),
                    cobranca.expiraEm(), cobranca.copiaECola(), cobranca.estado());
        }
    }

    record ComprovanteNaResposta(UUID vendaId, UUID usuarioId, Instant concluidoEm,
            List<LinhaDoComprovante> linhas, BigDecimal somaDosItens,
            BigDecimal descontoDaVenda, BigDecimal valorTotal,
            List<ParcelaDoComprovante> parcelas, BigDecimal troco,
            BigDecimal valorFiado, BigDecimal saldoDevedor) {
        static ComprovanteNaResposta de(Comprovante comprovante) {
            return new ComprovanteNaResposta(comprovante.vendaId(), comprovante.usuarioId(),
                    comprovante.concluidoEm(), comprovante.linhas().stream()
                            .map(LinhaDoComprovante::de).toList(),
                    comprovante.somaDosItens().valor(), comprovante.descontoDaVenda().valor(),
                    comprovante.valorTotal().valor(), comprovante.parcelas().stream()
                            .map(ParcelaDoComprovante::de).toList(), comprovante.troco().valor(),
                    comprovante.valorFiado().valor(), comprovante.saldoDevedor().valor());
        }
    }

    record LinhaDoComprovante(UUID produtoId, String nome, String unidade,
            BigDecimal quantidade, BigDecimal precoUnitario, BigDecimal valorBruto,
            BigDecimal desconto, BigDecimal subtotal) {
        static LinhaDoComprovante de(Comprovante.Linha linha) {
            return new LinhaDoComprovante(linha.produtoId(), linha.nome(), linha.unidade(),
                    linha.quantidade(), linha.precoUnitario().valor(), linha.valorBruto().valor(),
                    linha.desconto().valor(), linha.subtotal().valor());
        }
    }

    record ParcelaDoComprovante(FormaPagamento forma, BigDecimal valor, BigDecimal troco) {
        static ParcelaDoComprovante de(Comprovante.Parcela parcela) {
            return new ParcelaDoComprovante(parcela.forma(), parcela.valor().valor(),
                    parcela.troco().valor());
        }
    }
}
