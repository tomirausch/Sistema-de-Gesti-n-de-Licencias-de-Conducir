package tp.metodosAgiles.gestionLicencias.services;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tp.metodosAgiles.gestionLicencias.dto.LicenciaDTO;
import tp.metodosAgiles.gestionLicencias.entity.Licencia;
import tp.metodosAgiles.gestionLicencias.entity.Titular;
import tp.metodosAgiles.gestionLicencias.entity.enums.ClaseLicencia;
import tp.metodosAgiles.gestionLicencias.entity.enums.EstadoLicencia;
import tp.metodosAgiles.gestionLicencias.repository.LicenciaRepository;
import tp.metodosAgiles.gestionLicencias.util.DateUtils;
import tp.metodosAgiles.gestionLicencias.util.TextUtils;

@Service
public class LicenciaService {

    @Autowired
    private TitularService titularService;

    // Inyecto el Repositorio de Licencias para poder realizar consultas a la base de datos
    @Autowired
    private LicenciaRepository licenciaRepository;
    // --------------------------------------------------

    public boolean validarEdadMinima(LocalDate fechaNacimiento, String claseSolicitada) {
        int edad = calcularEdad(fechaNacimiento);

        // Lógica para Clases C, D y E (mínimo 21 años)
        if (claseSolicitada.matches("[CDE]")) {
            return edad >= 21;
        }

        // Lógica para el resto de las clases (mínimo 17 años)
        return edad >= 17;
    }

    public boolean validarEdadMaximaProfesionalPrimeraVez(LocalDate fechaNacimiento, String claseSolicitada,
            boolean esPrimeraVez) {
        int edad = calcularEdad(fechaNacimiento);

        // Lógica para no otorgar profesional por primera vez a mayores de 65
        if (claseSolicitada.matches("[CDE]") && esPrimeraVez) {
            return edad <= 65;
        }

        return true;
    }

    private int calcularEdad(LocalDate fechaNacimiento) {
        return Period.between(fechaNacimiento, LocalDate.now()).getYears();
    }

    // Metodo para calcular la vigencia de la Licencia
    public int calcularVigencia(Licencia licencia) {
        Titular titular = licencia.getTitular();
        LocalDate fechaNacimiento = titular.getFechaNacimiento();
        int edad = calcularEdad(fechaNacimiento);

        boolean esPrimeraVez = titularService.esPrimeraLicencia(licencia);

        // caso imposible
        if (edad < 17)
            return -1;

        // casos reales
        if (edad >= 17 && edad <= 21) {
            if (esPrimeraVez)
                return 1;
            else
                return 3;
        } else if (edad <= 46)
            return 5;
        else if (edad <= 60)
            return 4;
        else if (edad <= 70)
            return 3;
        else
            return 1;

    }

    public int calcularCostoLicencia(int vigencia, ClaseLicencia clase) {
        if (clase == null) {
            return -1;
        }
        return switch (clase) {
            case A, B, G -> switch (vigencia) {
                case 5 -> 40;
                case 4 -> 30;
                case 3 -> 25;
                case 1 -> 20;
                default -> -1;
            };
            case C -> switch (vigencia) {
                case 5 -> 47;
                case 4 -> 35;
                case 3 -> 30;
                case 1 -> 23;
                default -> -1;
            };
            case E -> switch (vigencia) {
                case 5 -> 59;
                case 4 -> 44;
                case 3 -> 39;
                case 1 -> 29;
                default -> -1;
            };
            default -> -1;
        };
    }

    // Método para validar historial Clase B 
    public boolean validarHistorialProfesional(Long titularId, String claseSolicitada) {
        if (!claseSolicitada.matches("[CDE]")) {
            return true;
        }

        java.util.Optional<Licencia> licenciaB = licenciaRepository
                .findFirstByTitularIdAndClaseOrderByFechaEmisionAsc(titularId, ClaseLicencia.B);

        if (licenciaB.isPresent()) {
            java.time.LocalDate fechaEmisionB = licenciaB.get().getFechaEmision();
            java.time.LocalDate haceUnAnioExacto = java.time.LocalDate.now().minusYears(1);

            return !fechaEmisionB.isAfter(haceUnAnioExacto);
        }

        return false;
    }

    public List<LicenciaDTO> buscarLicencia(String nroDocumento, String apellido, String estado, String clase) {
        List<Licencia> licenciasSinFiltro = licenciaRepository.findAll();

        List<Licencia> licencias = new ArrayList<>();
        for (Licencia l : licenciasSinFiltro) {

            boolean coincideApellido = apellido == null ||
                    apellido.isBlank() ||
                    TextUtils.normalizarString(l.getTitular().getApellido())
                            .contains(TextUtils.normalizarString(apellido));

            boolean coincideDocumento = nroDocumento == null ||
                    nroDocumento.isBlank() ||
                    TextUtils.normalizarString(l.getTitular().getNroDocumento())
                            .contains(TextUtils.normalizarString(nroDocumento));

            boolean coincideEstado = estado == null ||
                    estado.isBlank() ||
                    TextUtils.normalizarString(obtenerEstadoLicencia(l).toString())
                            .contains(TextUtils.normalizarString(estado));

            boolean coincideClase = clase == null ||
                    clase.isBlank() ||
                    TextUtils.normalizarString(l.getClase().toString())
                            .contains(TextUtils.normalizarString(clase));

            if (coincideApellido && coincideDocumento && coincideEstado && coincideClase) {
                licencias.add(l);
            }
        }

        return licencias.stream()
                .map(licencia -> LicenciaDTO.toResponse(licencia, obtenerEstadoLicencia(licencia)))
                .collect(Collectors.toList());
    }

    public EstadoLicencia obtenerEstadoLicencia(Licencia licencia) {
        boolean expirada = licencia.getFechaVencimiento().isBefore(LocalDate.now());
        if (expirada)
            return EstadoLicencia.EXPIRADA;
        else
            return EstadoLicencia.VIGENTE;
    }

    public LicenciaDTO buscarLicenciaPorTitular(String nroDocumento) {

        Licencia licencia = licenciaRepository.findByTitular_NroDocumento(nroDocumento)
                .orElseThrow(() -> new RuntimeException("No se encontró la licencia"));
        return LicenciaDTO.toResponse(licencia, obtenerEstadoLicencia(licencia));
    }

    public boolean puedeRenovarLicencia(String nroDocumento, boolean modificacionDatos) {

        Licencia licencia = licenciaRepository
                .findFirstByTitular_NroDocumentoOrderByFechaVencimientoDesc(nroDocumento)
                .orElseThrow(() -> new RuntimeException("El titular no posee licencias"));

        boolean vencida = licencia.getFechaVencimiento().isBefore(LocalDate.now());

        return vencida || modificacionDatos;
    }

    public Licencia renovarLicencia(String nroDocumento, boolean modificacionDatos) {
        // 1. Invocamos la validacion
        if (!puedeRenovarLicencia(nroDocumento, modificacionDatos)) {
            throw new RuntimeException("La licencia actual aún está vigente y no hay modificación de datos.");
        }

        // 2. Buscamos la última licencia para copiar los datos 
        Licencia licenciaAnterior = licenciaRepository
                .findFirstByTitular_NroDocumentoOrderByFechaVencimientoDesc(nroDocumento)
                .orElseThrow(() -> new RuntimeException("El titular no posee licencias"));

        // 3. Armamos la nueva entidad
        Licencia nuevaLicencia = new Licencia();
        nuevaLicencia.setTitular(licenciaAnterior.getTitular());
        nuevaLicencia.setClase(licenciaAnterior.getClase());
        nuevaLicencia.setObservacionesLimitaciones(licenciaAnterior.getObservacionesLimitaciones());
        nuevaLicencia.setFechaEmision(LocalDate.now()); // Fecha actual
        nuevaLicencia.setUsuarioAdministrador(licenciaAnterior.getUsuarioAdministrador());

        // 4. Calculamos nueva vigencia y fecha de vencimiento
        int aniosVigencia = calcularVigencia(nuevaLicencia);
        LocalDate nuevoVencimiento = tp.metodosAgiles.gestionLicencias.util.DateUtils
                .calcularVencimientoProximoCumpleanios(
                        nuevaLicencia.getTitular().getFechaNacimiento(),
                        aniosVigencia);
        nuevaLicencia.setFechaVencimiento(nuevoVencimiento);

        // 5. Guardamos el nuevo registro manteniendo el historial previo
        return licenciaRepository.save(nuevaLicencia);
    }

    public List<LicenciaDTO> obtenerLicenciasExpiradas() {

        return licenciaRepository
                .findByFechaVencimientoBeforeOrderByFechaVencimientoDesc(
                        LocalDate.now())
                .stream()
                .map(l -> LicenciaDTO.toResponse(l,
                        EstadoLicencia.EXPIRADA))
                .toList();
    }

    public List<LicenciaDTO> buscarLicenciasVigentesConFiltros(String nombre, String apellido, String grupoSanguineo,
            String factorRh, Boolean donante) {
        // Ejecutamos la consulta en la BD pasando la fecha actual para garantizar la vigencia
        List<Licencia> vigentes = licenciaRepository.findLicenciasVigentesByFiltros(
                LocalDate.now(), nombre, apellido, grupoSanguineo, factorRh, donante);

        // Mapeamos los resultados a DTO marcando el estado explícitamente como VIGENTE
        return vigentes.stream()
                .map(licencia -> LicenciaDTO.toResponse(licencia, EstadoLicencia.VIGENTE))
                .collect(Collectors.toList());
    }

    public String obtenerNuevaFechaVigencia(String nroDocumento) {
        Licencia licencia = licenciaRepository.findFirstByTitular_NroDocumentoOrderByFechaVencimientoDesc(nroDocumento)
                .orElseThrow(() -> new RuntimeException("No se encontró la licencia"));
        String fechaNuevaVigencia = DateUtils.formatearFecha(DateUtils
                .calcularVencimientoProximoCumpleanios(LocalDate.now(), calcularVigencia(licencia)));
        return fechaNuevaVigencia;
    }

}