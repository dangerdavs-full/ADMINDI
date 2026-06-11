import api from "./api";

export type MaintenanceTicket = {
  id: string;
  ownerId: string;
  propertyId: string;
  tenantProfileId?: string;
  title: string;
  description?: string;
  urgency: string;
  status: string;
  assignedProviderId?: string;
  providerAcceptedAt?: string;
  createdAt: string;
  resolvedAt?: string;
};

export type MaintenanceExpense = {
  id: string;
  ownerId: string;
  propertyId: string;
  type: string;
  amount: string;
  status: string;
  approvedAmount?: string;
  paidAmount?: string;
  outstandingAmount?: string;
  paymentSettlementStatus?: string;
  paymentMethod?: string;
  ownerConfirmationStatus?: string;
  providerConfirmationStatus?: string;
  providerUserId?: string;
};

export async function listMaintenanceTickets(propertyId?: string): Promise<MaintenanceTicket[]> {
  const { data } = await api.get<MaintenanceTicket[]>("/maintenance/tickets", {
    params: propertyId ? { propertyId } : {},
  });
  return data;
}

export async function listMyMaintenanceTicketsAsProvider(): Promise<MaintenanceTicket[]> {
  const { data } = await api.get<MaintenanceTicket[]>("/maintenance/tickets/my");
  return data;
}

export async function acceptMaintenanceTicket(ticketId: string): Promise<MaintenanceTicket> {
  const { data } = await api.post<MaintenanceTicket>(`/maintenance/tickets/${ticketId}/accept`);
  return data;
}

export async function recordMaintenanceExpensePayment(
  expenseId: string,
  amount: string,
  paymentMethod: string
): Promise<MaintenanceExpense> {
  const { data } = await api.post<MaintenanceExpense>(`/maintenance/expenses/${expenseId}/payments`, {
    amount,
    paymentMethod,
  });
  return data;
}

export async function confirmMaintenanceExpenseAsProvider(
  expenseId: string,
  outcome: "FULL" | "PARTIAL" | "DISPUTE",
  note?: string
): Promise<MaintenanceExpense> {
  const { data } = await api.put<MaintenanceExpense>(
    `/maintenance/expenses/${expenseId}/provider-confirmation`,
    { outcome, note }
  );
  return data;
}
