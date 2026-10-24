package br.com.caixasimples.shared;

/** Uma remoção não prossegue quando sua prova externa não pôde ser guardada. */
public class RegistroDeRemocoesIndisponivelException extends RuntimeException {

    public RegistroDeRemocoesIndisponivelException() {
        super("O registro de remoções está fora do ar; tente de novo em alguns minutos.");
    }
}
