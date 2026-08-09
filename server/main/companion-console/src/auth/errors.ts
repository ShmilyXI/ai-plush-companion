export class AuthProtocolError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AuthProtocolError'
  }
}
