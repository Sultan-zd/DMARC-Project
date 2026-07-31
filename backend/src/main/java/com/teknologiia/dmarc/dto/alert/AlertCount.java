package com.teknologiia.dmarc.dto.alert;

public record AlertCount(
    Long total,
    Long unread,
    Long critical,
    Long high
) {}
