export interface ConversationSelectionToken {
  readonly conversationId: string
  readonly generation: number
}

/**
 * Prevents an older history request from committing after a newer selection
 * has started. The selected id is checked separately so Android Back can
 * invalidate a request without needing to start another one.
 */
export class ConversationSelectionGeneration {
  private generation = 0

  begin(conversationId: string): ConversationSelectionToken {
    this.generation += 1
    return { conversationId, generation: this.generation }
  }

  owns(token: ConversationSelectionToken): boolean {
    return token.generation === this.generation
  }

  isCurrent(
    token: ConversationSelectionToken,
    selectedConversationId?: string | null,
  ): boolean {
    return this.owns(token) && token.conversationId === selectedConversationId
  }

  invalidate(): void {
    this.generation += 1
  }
}
