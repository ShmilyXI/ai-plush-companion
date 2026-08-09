declare module 'sm-crypto' {
  export const sm2: {
    doEncrypt: (plainText: string, publicKey: string, cipherMode?: number) => string
    generateKeyPairHex: () => { publicKey: string; privateKey: string }
  }
}
