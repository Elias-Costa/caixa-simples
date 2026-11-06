package br.com.caixasimples.vendas.application;

/**
 * A venda nova foi pedida num caixa que já não está aberto.
 *
 * <p>Com rede, é a recusa de sempre, um estado inválido que a tela mostra como veio, e a frase diz a
 * saída de quem ainda vai vender: abrir um caixa. O tipo próprio existe porque a mesma recusa chega
 * também pelo lote do dispositivo, para uma venda que já aconteceu no balcão, e ali a saída é outra;
 * quem aplica o lote a reconhece por este tipo e escreve a frase dela.
 */
public class CaixaFechadoParaVendaNovaException extends IllegalStateException {

    public CaixaFechadoParaVendaNovaException() {
        super("Este caixa já foi fechado e não aceita venda nova; abra um caixa antes de vender.");
    }
}
