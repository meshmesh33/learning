package com.example.pim.application.port.out;

import com.example.pim.domain.model.PublicationSequence;

public interface PublicationSequenceGenerator {

    /** Strictly increasing across all product sets and all PIM instances. */
    PublicationSequence next();
}
