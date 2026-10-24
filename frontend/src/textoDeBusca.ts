/**
 * O texto como as buscas o comparam: aparado, sem acento e em minúsculas. A decomposição separa a
 * letra do acento, e as marcas que sobram saem, então Água vira agua e Pão vira pao. No tablet quase
 * ninguém digita acento. O servidor faz a mesma conta na busca de produto do balcão, para a mesma
 * palavra achar os mesmos itens com e sem rede.
 */
export function paraBusca(texto: string): string {
  return texto.trim().normalize('NFD').replace(/\p{M}+/gu, '').toLocaleLowerCase('pt-BR')
}
