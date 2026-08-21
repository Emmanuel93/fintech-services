package com.fintech.scoring.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "circulo_scores", schema = "scoring")
public class CirculoScore {

    @Id private UUID scoreId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private CirculoReport report;

    @Column(length = 20) private String nombreScore;
    private Integer valor;
    @Column(length = 500) private String razones;

    protected CirculoScore() {}

    public CirculoScore(UUID scoreId, CirculoReport report,
                        String nombreScore, Integer valor, String razones) {
        this.scoreId = scoreId;
        this.report = report;
        this.nombreScore = nombreScore;
        this.valor = valor;
        this.razones = razones;
    }

    public UUID getScoreId()        { return scoreId; }
    public String getNombreScore()  { return nombreScore; }
    public Integer getValor()       { return valor; }
    public String getRazones()      { return razones; }
}
