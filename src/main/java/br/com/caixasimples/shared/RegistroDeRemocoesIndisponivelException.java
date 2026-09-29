package br.com.caixasimples.shared;

/** Uma remoção não prossegue quando sua prova externa não pôde ser guardada. */
public class RegistroDeRemocoesIndisponivelException extends RuntimeException {

    public RegistroDeRemocoesIndisponivelException() {
        super("o registro de remocao esta indisponivel; tente novamente");
    }
}
