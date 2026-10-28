package com.aicallagent.app.local;

/** Whitelisted diagnostic code; never retains native error text or call contents. */
final class ReplyFailure extends Exception {
    final String code;
    ReplyFailure(String code) { super(code); this.code = code; }
}
