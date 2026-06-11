import api from './api';

export interface UnitDTO {
  id?: string;
  propertyId: string;
  propertyName?: string;
  name: string;
  type: string;
  status?: 'VACANT' | 'OCCUPIED' | 'MAINTENANCE';
  squareMeters?: number;
  bedrooms?: number;
  bathrooms?: number;
  floorCode?: string;
  notes?: string;
}

export const unitService = {
  createUnit: async (data: UnitDTO): Promise<UnitDTO> => {
    const res = await api.post('/units', data);
    return res.data;
  },

  getUnitsForProperty: async (propertyId: string): Promise<UnitDTO[]> => {
    const res = await api.get(`/units/property/${propertyId}`);
    return res.data;
  },

  updateUnit: async (id: string, data: UnitDTO): Promise<UnitDTO> => {
    const res = await api.put(`/units/${id}`, data);
    return res.data;
  }
};
