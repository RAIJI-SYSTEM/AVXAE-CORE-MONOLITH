package com.raiji.deliveries.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.raiji.common.notification.NotificationRequested;
import com.raiji.deliveries.domain.Delivery;
import com.raiji.users.company.CompanyMembership;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryNotifierTest {

    @Mock CompanyMembership membership;
    @Mock ApplicationEventPublisher events;
    @InjectMocks DeliveryNotifier notifier;

    private final UUID companyId = UUID.randomUUID();

    @Test
    void encomendaDeEmpresaAvisaSoOsMembrosAtivos() {
        Delivery d = new Delivery();
        d.setCompanyId(companyId);
        d.setSenderSub("sender");
        when(membership.activeMemberSubs(companyId)).thenReturn(java.util.Arrays.asList("m1", null, "m2", "m1"));
        when(membership.isActiveMember(companyId, "sender")).thenReturn(true);

        notifier.sender(d, "DELIVERY_ACCEPTED", "aceita");

        ArgumentCaptor<NotificationRequested> captor = ArgumentCaptor.forClass(NotificationRequested.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationRequested::recipientSub).containsExactly("m1", "m2");
        assertThat(captor.getAllValues()).allMatch(n -> n.audience().equals("COMPANY"));
    }

    @Test
    void encomendaDeEmpresaTambemAvisaORemetenteQuandoNaoEMembroAtivo() {
        Delivery d = new Delivery();
        d.setCompanyId(companyId);
        d.setSenderSub("sender");
        when(membership.activeMemberSubs(companyId)).thenReturn(List.of("m1"));
        when(membership.isActiveMember(companyId, "sender")).thenReturn(false);

        notifier.sender(d, "X", "msg");

        ArgumentCaptor<NotificationRequested> captor = ArgumentCaptor.forClass(NotificationRequested.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues().get(1).recipientSub()).isEqualTo("sender");
        assertThat(captor.getAllValues().get(1).audience()).isEqualTo("PASSENGER");
    }

    @Test
    void encomendaPessoalAvisaSoORemetente() {
        Delivery d = new Delivery();
        d.setSenderSub("sender");
        notifier.sender(d, "X", "msg");

        ArgumentCaptor<NotificationRequested> captor = ArgumentCaptor.forClass(NotificationRequested.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().recipientSub()).isEqualTo("sender");
        assertThat(captor.getValue().kind()).isEqualTo("X");
        assertThat(captor.getValue().message()).isEqualTo("msg");
    }

    @Test
    void atalhosDePassageiroEMotorista() {
        UUID rideId = UUID.randomUUID();
        UUID ref = UUID.randomUUID();

        notifier.passenger("p1", "K1", rideId, ref, "a");
        notifier.driver("d1", "K2", rideId, null, "b");

        ArgumentCaptor<NotificationRequested> captor = ArgumentCaptor.forClass(NotificationRequested.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues().get(0)).isEqualTo(new NotificationRequested("p1", "PASSENGER", "K1", rideId, ref, "a"));
        assertThat(captor.getAllValues().get(1)).isEqualTo(new NotificationRequested("d1", "DRIVER", "K2", rideId, null, "b"));
    }

    @Test
    void empresaSemMembrosNaoPublicaNada() {
        when(membership.activeMemberSubs(companyId)).thenReturn(List.of());
        notifier.company(companyId, "K", null, null, "m");
        verifyNoInteractions(events);
        verify(membership).activeMemberSubs(companyId);
        verify(events, org.mockito.Mockito.never()).publishEvent(any());
    }
}
