package com.raiji.deliveries.service;

import com.raiji.deliveries.domain.PackageDetails;
import com.raiji.deliveries.domain.PackageSize;
import com.raiji.deliveries.dto.PackageRequest;
import com.raiji.rides.cargo.CargoLoad;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Monta e confere os dados do pacote. */
public final class Packages {

    private Packages() {
    }

    public static PackageDetails from(PackageRequest r) {
        PackageSize size = r.size();
        if (r.weightKg().compareTo(size.maxWeightKg) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O peso passa do máximo para o tamanho " + size + " (" + size.maxWeightKg.toPlainString()
                            + " kg). Escolha um tamanho maior.");
        }
        PackageDetails p = new PackageDetails();
        p.setSize(size);
        p.setDescription(r.description().trim());
        p.setWeightKg(r.weightKg());
        p.setLengthCm(r.lengthCm() != null ? r.lengthCm() : size.lengthCm);
        p.setWidthCm(r.widthCm() != null ? r.widthCm() : size.widthCm);
        p.setHeightCm(r.heightCm() != null ? r.heightCm() : size.heightCm);
        p.setFragile(r.fragile());
        p.setDeclaredValue(r.declaredValue());
        p.setRecipientName(r.recipientName().trim());
        p.setRecipientPhone(r.recipientPhone().trim());
        return p;
    }

    public static CargoLoad load(PackageDetails p) {
        return CargoLoad.of(p.getLengthCm(), p.getWidthCm(), p.getHeightCm(), 1, p.getWeightKg());
    }
}
